package brain.desktop

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.runtime.*
import brain.studio.AudioTelemetry
import kotlinx.coroutines.*
import java.nio.file.*
import javax.sound.sampled.*

class DesktopAudio(
    private val store: FileBrainStore,
    private val ffmpeg: String,
    private val root: Path,
    private val scope: CoroutineScope,
) : PlaybackSessionGateway {
    private var job: Job? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var generation = 0L
    @Volatile private var phase = PlaybackPhase.IDLE
    @Volatile private var level = 0f
    @Volatile private var offset = 0.0
    @Volatile private var duration = 0.0
    @Volatile private var rate = 1.0
    @Volatile private var sourceId: String? = null
    @Volatile private var compactSource = false

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
        require(rate in .5..2.0 && fromSeconds.isFinite() && fromSeconds >= 0)
        stop()
        val capture = store.capture(captureId) ?: error("Audio unavailable")
        sourceId = captureId
        compactSource = compact
        this.offset = fromSeconds.coerceAtMost(capture.durationSeconds.coerceAtLeast(0.0))
        this.duration = capture.durationSeconds.coerceAtLeast(0.0)
        this.rate = rate
        val own = generation
        phase = PlaybackPhase.LOADING
        val path = withContext(Dispatchers.IO) {
            val target = Files.createTempFile(root, ".playback-", ".wav")
            try {
                JvmCommandRunner().run(
                    listOf(
                        ffmpeg, "-nostdin", "-v", "error", "-y",
                        "-ss", this@DesktopAudio.offset.toString(),
                        "-i", store.resolveAudio(capture, compact).toString(),
                        "-af", "atempo=$rate", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                        target.toString(),
                    ),
                    300,
                )
                target
            } catch (e: Exception) {
                Files.deleteIfExists(target)
                if (generation == own) phase = PlaybackPhase.IDLE
                throw e
            }
        }
        if (generation != own) {
            Files.deleteIfExists(path)
            return
        }
        val stream = withContext(Dispatchers.IO) { AudioSystem.getAudioInputStream(path.toFile()) }
        val output = try {
            withContext(Dispatchers.IO) {
                AudioSystem.getSourceDataLine(stream.format).also { it.open(stream.format); it.start() }
            }
        } catch (e: Exception) {
            stream.close()
            Files.deleteIfExists(path)
            phase = PlaybackPhase.IDLE
            throw e
        }
        line = output
        phase = PlaybackPhase.PLAYING
        job = scope.launch(Dispatchers.IO) {
            try {
                stream.use { audio ->
                    val buffer = ByteArray(4096)
                    while (isActive && generation == own) {
                        if (phase == PlaybackPhase.PAUSED) {
                            delay(20)
                            continue
                        }
                        val count = audio.read(buffer)
                        if (count < 0) break
                        level = SignalLevel.pcm16(buffer, count)
                        output.write(buffer, 0, count)
                    }
                    if (isActive && generation == own) output.drain()
                }
            } finally {
                output.close()
                if (generation == own) {
                    line = null
                    phase = PlaybackPhase.IDLE
                    level = 0f
                }
                Files.deleteIfExists(path)
            }
        }
    }

    override suspend fun pause() {
        check(phase == PlaybackPhase.PLAYING)
        phase = PlaybackPhase.PAUSED
        line?.stop()
        level = 0f
    }

    override suspend fun resume() {
        check(phase == PlaybackPhase.PAUSED)
        line?.start()
        phase = PlaybackPhase.PLAYING
    }

    override fun playbackState(): PlaybackSessionState {
        val pos = runCatching {
            offset + (line?.longFramePosition ?: 0) / 16000.0 * rate
        }.getOrDefault(offset).coerceIn(0.0, duration.coerceAtLeast(0.0))
        return PlaybackSessionState(
            phase = phase,
            sourceId = sourceId,
            positionSeconds = if (phase == PlaybackPhase.IDLE) 0.0 else pos,
            durationSeconds = duration,
            level = level.coerceIn(0f, 1f),
        )
    }

    override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState {
        val before = playbackState()
        val target = before.seekTarget(positionSeconds) ?: error("audioFailed")
        val source = before.sourceId ?: error("audioFailed")
        val wasPaused = before.phase == PlaybackPhase.PAUSED
        playCapture(source, compactSource, target, rate)
        if (wasPaused && target < duration) pause()
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
        generation++
        job?.cancel()
        job = null
        line?.stop()
        line?.close()
        line = null
        phase = PlaybackPhase.IDLE
        level = 0f
        offset = 0.0
    }
}
