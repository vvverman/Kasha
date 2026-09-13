package ru.vrmn.kasha.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import brain.domain.AudioGateway
import brain.studio.AudioTelemetry
import brain.studio.SignalLevel
import kotlinx.coroutines.*
import java.io.FileInputStream

internal class AndroidAudio(
    private val repository: AndroidRepository,
    private val scope: CoroutineScope,
) : AudioGateway {
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var phase = "idle"
    @Volatile private var signal = 0f
    @Volatile private var offset = 0.0
    @Volatile private var duration = 0.0
    @Volatile private var rate = 1.0
    @Volatile private var generation = 0L

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
        require(rate in .5..2.0 && fromSeconds >= 0.0)
        val file = repository.audioFile(captureId) ?: error("Audio unavailable")
        stop()
        val own = generation
        this.offset = fromSeconds
        this.duration = file.length().toDouble() / (AndroidIntelligence.SAMPLE_RATE * 2.0)
        this.rate = rate

        val min = AudioTrack.getMinBufferSize(
            AndroidIntelligence.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AndroidIntelligence.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        created.playbackParams = PlaybackParams().setSpeed(rate.toFloat()).setPitch(1f)
        track = created
        phase = "playing"
        created.play()

        job = scope.launch(Dispatchers.IO) {
            try {
                FileInputStream(file).use { input ->
                    val skip = (fromSeconds * AndroidIntelligence.SAMPLE_RATE * 2.0).toLong().coerceAtMost(file.length())
                    var left = skip
                    while (left > 0) {
                        val moved = input.skip(left)
                        if (moved <= 0) break
                        left -= moved
                    }
                    val buffer = ByteArray(8192)
                    while (isActive && generation == own) {
                        if (phase == "paused") { delay(20); continue }
                        val count = input.read(buffer)
                        if (count < 0) break
                        signal = SignalLevel.pcm16(buffer, count)
                        var written = 0
                        while (written < count && isActive && generation == own) {
                            val n = created.write(buffer, written, count - written, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            written += n
                        }
                    }
                }
            } finally {
                runCatching { created.stop() }
                created.release()
                if (generation == own) {
                    track = null; phase = "idle"; signal = 0f
                }
            }
        }
    }

    override suspend fun pause() {
        check(phase == "playing")
        track?.pause(); phase = "paused"; signal = 0f
    }

    override suspend fun resume() {
        check(phase == "paused")
        track?.play(); phase = "playing"
    }

    override fun telemetry(): AudioTelemetry {
        val frames = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
        val position = (offset + frames.toDouble() / AndroidIntelligence.SAMPLE_RATE * rate)
            .coerceIn(0.0, duration.coerceAtLeast(0.0))
        return AudioTelemetry(phase, if (phase == "idle") 0.0 else position, duration, signal)
    }

    override fun stop() {
        generation++
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null; phase = "idle"; signal = 0f; offset = 0.0
    }
}
