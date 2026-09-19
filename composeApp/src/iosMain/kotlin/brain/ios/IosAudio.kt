@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.studio.AudioTelemetry
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.Foundation.NSURL

internal class IosAudio(
    private val repository: IosRepository,
) : PlaybackSessionGateway {
    private var player: AVAudioPlayer? = null
    private var paused = false
    private var sourceId: String? = null

    // AVAudioPlayer может сбросить currentTime при окончании. Позиция не заменяет callback.
    private val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            if (this@IosAudio.player == player) stop()
        }
        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            if (this@IosAudio.player == player) stop()
        }
    }

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

        var created: AVAudioPlayer? = null
        try {
            val next = AVAudioPlayer(NSURL.fileURLWithPath(path), error = null)
            created = next
            next.delegate = delegate
            next.enableRate = true
            next.rate = rate.toFloat().coerceIn(1f, 2f)
            next.currentTime = fromSeconds.coerceAtLeast(0.0).coerceAtMost(next.duration)
            check(next.prepareToPlay() && next.play()) { "audioFailed" }
            player = next
            sourceId = captureId
            paused = false
        } catch (error: Throwable) {
            created?.delegate = null
            created?.stop()
            sourceId = previousSource
            IosAudioSessionBridge.deactivate()
            throw error
        }
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
        // Пауза рядом с концом остаётся паузой. Только системное завершение освобождает player.
        val phase = if (active.playing) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
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
        player?.delegate = null
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
