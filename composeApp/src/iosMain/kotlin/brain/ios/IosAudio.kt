@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.studio.AudioTelemetry
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL

internal class IosAudio(
    private val repository: IosRepository,
) : PlaybackSessionGateway {
    private var player: AVAudioPlayer? = null
    private var paused = false
    private var sourceId: String? = null

    init {
        IosAudioSessionBridge.observeSystemEvents(
            IosAudioSystemObserver.PLAYBACK,
            ::handleSystemEvent,
        )
    }

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
        val path = repository.audioPath(captureId) ?: error("Аудиофайл записи не найден")
        val previousSource = sourceId
        stop()
        IosAudioSessionBridge.activatePlayback()

        val created = AVAudioPlayer(NSURL.fileURLWithPath(path), error = null)
        created.enableRate = true
        created.rate = rate.toFloat().coerceIn(1f, 2f)
        created.currentTime = fromSeconds.coerceAtLeast(0.0).coerceAtMost(created.duration)
        if (!created.prepareToPlay() || !created.play()) {
            sourceId = previousSource
            IosAudioSessionBridge.deactivate()
            error("audioFailed")
        }
        player = created
        sourceId = captureId
        paused = false
    }

    override suspend fun pause() {
        val active = player ?: return
        active.pause()
        paused = true
    }

    override suspend fun resume() {
        val active = player ?: return
        IosAudioSessionBridge.activatePlayback()
        check(active.play()) { "audioFailed" }
        paused = false
    }

    override fun playbackState(): PlaybackSessionState {
        val active = player ?: return PlaybackSessionState(sourceId = sourceId)
        val duration = active.duration.coerceAtLeast(0.0)
        val position = active.currentTime.coerceAtLeast(0.0).coerceAtMost(duration)
        val phase = when {
            active.playing -> PlaybackPhase.PLAYING
            duration > 0.0 && position >= duration - 0.05 -> PlaybackPhase.IDLE
            else -> PlaybackPhase.PAUSED
        }
        if (phase == PlaybackPhase.IDLE) {
            active.stop()
            player = null
            paused = false
            IosAudioSessionBridge.deactivate()
            return PlaybackSessionState(sourceId = sourceId, durationSeconds = duration)
        }
        if (phase == PlaybackPhase.PAUSED) paused = true
        return PlaybackSessionState(
            phase = phase,
            sourceId = sourceId,
            positionSeconds = position,
            durationSeconds = duration,
        )
    }

    override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState {
        val before = playbackState()
        val target = before.seekTarget(positionSeconds) ?: error("audioFailed")
        val active = player ?: error("audioFailed")
        active.currentTime = target
        when (before.phase) {
            PlaybackPhase.PLAYING -> {
                if (target < active.duration && !active.playing) {
                    IosAudioSessionBridge.activatePlayback()
                    check(active.play()) { "audioFailed" }
                }
                paused = false
            }
            PlaybackPhase.PAUSED -> {
                if (active.playing) active.pause()
                paused = true
            }
            else -> error("audioFailed")
        }
        return playbackState()
    }

    override fun telemetry(): AudioTelemetry {
        val state = playbackState()
        return AudioTelemetry(
            phase = state.phase.legacyValue,
            position = state.positionSeconds,
            duration = state.durationSeconds,
            level = state.level,
        )
    }

    override fun stop() {
        player?.stop()
        player = null
        paused = false
        IosAudioSessionBridge.deactivate()
    }

    private fun handleSystemEvent(event: IosAudioSystemEvent) {
        val active = player ?: return
        if (IosPlaybackLifecycle.shouldPause(event)) {
            if (active.playing) active.pause()
            if (active.currentTime < active.duration - 0.05) paused = true
            return
        }
        if (
            event is IosAudioSystemEvent.ApplicationDidBecomeActive &&
            !active.playing &&
            active.currentTime < active.duration - 0.05
        ) {
            paused = true
        }
    }
}
