@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.AudioGateway
import brain.studio.AudioTelemetry
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSURL

internal class IosAudio(
    private val repository: IosRepository,
) : AudioGateway {
    private var player: AVAudioPlayer? = null
    private var paused = false

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
        val path = repository.audioPath(captureId) ?: error("Аудиофайл записи не найден")
        stop()

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)

        val created = AVAudioPlayer(NSURL.fileURLWithPath(path), error = null)
        created.enableRate = true
        created.rate = rate.toFloat().coerceIn(1f, 2f)
        created.currentTime = fromSeconds.coerceAtLeast(0.0).coerceAtMost(created.duration)
        check(created.prepareToPlay()) { "audioFailed" }
        check(created.play()) { "audioFailed" }
        player = created
        paused = false
    }

    override suspend fun pause() {
        val active = player ?: return
        active.pause()
        paused = true
    }

    override suspend fun resume() {
        val active = player ?: return
        check(active.play()) { "audioFailed" }
        paused = false
    }

    override fun telemetry(): AudioTelemetry {
        val active = player ?: return AudioTelemetry()
        val phase = when {
            active.playing -> "playing"
            paused -> "paused"
            active.currentTime >= active.duration - 0.05 -> "idle"
            else -> "paused"
        }
        if (phase == "idle") {
            stop()
            return AudioTelemetry()
        }
        return AudioTelemetry(
            phase = phase,
            position = active.currentTime,
            duration = active.duration,
        )
    }

    override fun stop() {
        player?.stop()
        player = null
        paused = false
    }
}
