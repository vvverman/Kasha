package ru.vrmn.kasha.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.domain.RecorderPhase
import brain.domain.TransportSnapshot
import brain.studio.AudioTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Один настоящий MediaPlayer. Продуктовые transport-правила делегируются Core. */
internal class AndroidAudio(
    context: Context,
    private val repository: AndroidStudioRepository,
    private val scope: CoroutineScope,
    private val recorderPhase: () -> RecorderPhase,
    private val foreground: () -> Boolean,
) : PlaybackSessionGateway, AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val control = Mutex()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (AndroidAudioPolicy.pauseOnFocusChange(change)) {
                if (state.phase == PlaybackPhase.LOADING) stopNow() else pauseNow()
            }
        }, main).build()
    private var player: MediaPlayer? = null
    private var prepared: CompletableDeferred<Unit>? = null
    private var seeking: CompletableDeferred<Unit>? = null
    private var metering: Job? = null
    @Volatile private var state = PlaybackSessionState()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (state.phase == PlaybackPhase.LOADING) stopNow() else pauseNow()
            }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(noisy, filter)
    }

    override fun playbackState(): PlaybackSessionState = state
    override fun telemetry(): AudioTelemetry = state.let {
        AudioTelemetry(it.phase.legacyValue, it.positionSeconds, it.durationSeconds, it.level)
    }

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) = control.withLock {
        require(fromSeconds.isFinite()) { "invalidPlaybackPosition" }
        val speed = AndroidAudioPolicy.rate(rate)
        val file = withContext(Dispatchers.IO) { repository.audioFile(captureId) }
        withContext(Dispatchers.Main.immediate) {
            check(foreground()) { "audioRequiresForeground" }
            check(TransportSnapshot(recorderPhase(), state).canStartPlayback) { "stopRecording" }
            stopNow()
            val created = MediaPlayer()
            val ready = CompletableDeferred<Unit>()
            player = created
            prepared = ready
            state = PlaybackSessionState(PlaybackPhase.LOADING, captureId)
            try {
                created.setAudioAttributes(attributes)
                created.setDataSource(file.absolutePath)
                created.setOnPreparedListener { source -> if (player === source) ready.complete(Unit) }
                created.setOnCompletionListener { source -> if (player === source) stopNow() }
                created.setOnErrorListener { source, _, _ ->
                    if (player === source) {
                        ready.completeExceptionally(IllegalStateException("audioPlaybackFailed"))
                        stopNow()
                    }
                    true
                }
                created.prepareAsync()
                withTimeout(15_000) { ready.await() }
                if (prepared === ready) prepared = null
                check(player === created) { "playbackSourceChanged" }
                val duration = created.duration / 1_000.0
                check(duration > 0.0) { "audioUnreadable" }
                val position = fromSeconds.coerceIn(0.0, duration)
                if (position > 0.0) seekPlayer(created, position)
                check(foreground() && TransportSnapshot(recorderPhase(), state).canStartPlayback) { "stopRecording" }
                PlaybackForegroundService.start(app)
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "audioFocusDenied" }
                created.start()
                created.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
                updateFromPlayer(PlaybackPhase.PLAYING)
                startMetering(created)
            } catch (error: Throwable) {
                if (player === created) stopNow()
                throw error
            }
        }
    }

    override suspend fun pause() = control.withLock {
        withContext(Dispatchers.Main.immediate) { pauseNow() }
    }

    override suspend fun resume() = control.withLock {
        withContext(Dispatchers.Main.immediate) {
            val active = player ?: error("audioNotLoaded")
            check(state.phase == PlaybackPhase.PAUSED) { "audioNotPaused" }
            check(foreground()) { "audioRequiresForeground" }
            check(TransportSnapshot(recorderPhase(), state).canStartPlayback) { "stopRecording" }
            try {
                PlaybackForegroundService.start(app)
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "audioFocusDenied" }
                active.start()
                updateFromPlayer(PlaybackPhase.PLAYING)
                startMetering(active)
            } catch (error: Exception) {
                stopNow()
                throw error
            }
        }
    }

    override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState = control.withLock {
        withContext(Dispatchers.Main.immediate) {
            val before = state
            val target = before.seekTarget(positionSeconds) ?: error("audioNotSeekable")
            val active = player ?: error("audioNotLoaded")
            check(TransportSnapshot(recorderPhase(), before).canStartPlayback) { "stopRecording" }
            if (target >= before.durationSeconds) {
                stopNow()
                state = before.copy(phase = PlaybackPhase.IDLE, positionSeconds = before.durationSeconds, level = 0f)
            } else {
                seekPlayer(active, target)
                if (player === active) updateFromPlayer(if (active.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED)
            }
            state
        }
    }

    override fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() }
    }

    override fun close() {
        stop()
        runCatching { app.unregisterReceiver(noisy) }
    }

    private fun pauseNow() {
        val active = player ?: return
        if (state.phase != PlaybackPhase.PLAYING) return
        try {
            active.pause()
            updateFromPlayer(PlaybackPhase.PAUSED)
            metering?.cancel()
            metering = null
            manager.abandonAudioFocusRequest(focus)
            PlaybackForegroundService.stop(app)
        } catch (_: Exception) { stopNow() }
    }

    private suspend fun seekPlayer(active: MediaPlayer, seconds: Double) {
        val ready = CompletableDeferred<Unit>()
        seeking?.completeExceptionally(CancellationException("seekReplaced"))
        seeking = ready
        active.setOnSeekCompleteListener { source -> if (player === source) ready.complete(Unit) }
        try {
            active.seekTo((seconds * 1_000).toLong(), MediaPlayer.SEEK_CLOSEST)
            withTimeout(5_000) { ready.await() }
        } finally {
            if (seeking === ready) seeking = null
            if (player === active) active.setOnSeekCompleteListener(null)
        }
    }

    private fun updateFromPlayer(phase: PlaybackPhase) {
        val active = player ?: return
        val id = state.sourceId ?: return
        val duration = (active.duration / 1_000.0).coerceAtLeast(0.0)
        state = PlaybackSessionState(phase, id, (active.currentPosition / 1_000.0).coerceIn(0.0, duration), duration)
    }

    private fun startMetering(active: MediaPlayer) {
        metering?.cancel()
        metering = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && player === active && state.phase == PlaybackPhase.PLAYING) {
                delay(100)
                if (player !== active || state.phase != PlaybackPhase.PLAYING) break
                try {
                    if (active.isPlaying) updateFromPlayer(PlaybackPhase.PLAYING) else pauseNow()
                } catch (_: Exception) { stopNow() }
            }
        }
    }

    private fun stopNow() {
        val old = player
        player = null
        state = PlaybackSessionState()
        prepared?.completeExceptionally(CancellationException("playbackStopped"))
        prepared = null
        seeking?.completeExceptionally(CancellationException("playbackStopped"))
        seeking = null
        metering?.cancel()
        metering = null
        runCatching { old?.release() }
        runCatching { manager.abandonAudioFocusRequest(focus) }
        runCatching { PlaybackForegroundService.stop(app) }
    }
}
