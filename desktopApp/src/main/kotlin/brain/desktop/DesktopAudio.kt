package brain.desktop

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.runtime.*
import brain.studio.AudioTelemetry
import brain.studio.SignalLevel
import kotlinx.coroutines.*
import java.nio.file.*
import javax.sound.sampled.*

class DesktopAudio(
    private val store: FileBrainStore,
    private val ffmpeg: String,
    private val root: Path,
    private val scope: CoroutineScope,
    private val runner: CommandRunner = JvmCommandRunner(),
    private val openStream: (Path) -> AudioInputStream = { AudioSystem.getAudioInputStream(it.toFile()) },
    private val createLine: (AudioFormat) -> SourceDataLine = AudioSystem::getSourceDataLine,
) : PlaybackSessionGateway {
    private val lifecycle = Any()
    private var job: Job? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var generation = 0L
    @Volatile private var phase = PlaybackPhase.IDLE
    @Volatile private var level = 0f
    private var offset = 0.0
    private var duration = 0.0
    private var rate = 1.0
    private var sourceId: String? = null
    private var compactSource = false

    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
        startCapture(captureId, compact, fromSeconds, rate, PlaybackPhase.PLAYING)
    }

    private suspend fun startCapture(
        captureId: String,
        compact: Boolean,
        fromSeconds: Double,
        rate: Double,
        targetPhase: PlaybackPhase,
    ) {
        require(rate in .5..2.0 && fromSeconds.isFinite() && fromSeconds >= 0)
        require(targetPhase == PlaybackPhase.PLAYING || targetPhase == PlaybackPhase.PAUSED)
        // Фиксируем поколение ДО первой приостановки. Stop и публикация аудиовыхода атомарны.
        val own = synchronized(lifecycle) {
            stopLocked()
            sourceId = captureId
            compactSource = compact
            this.rate = rate
            duration = 0.0
            phase = PlaybackPhase.LOADING
            generation
        }
        var path: Path? = null
        var stream: AudioInputStream? = null
        var output: SourceDataLine? = null
        var published = false
        fun release() {
            runCatching { output?.close() }
            runCatching { stream?.close() }
            path?.let { runCatching { Files.deleteIfExists(it) } }
        }
        try {
            val capture = store.capture(captureId) ?: error("Audio unavailable")
            val seconds = capture.durationSeconds.coerceAtLeast(0.0)
            val start = fromSeconds.coerceAtMost(seconds)
            synchronized(lifecycle) {
                if (generation != own) return
                offset = start
                duration = seconds
            }
            val decoded = withContext(Dispatchers.IO) {
                Files.createTempFile(root, ".playback-", ".wav").also { target ->
                    path = target
                    runner.run(
                        listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-ss", start.toString(),
                            "-i", store.resolveAudio(capture, compact).toString(), "-af", "atempo=$rate",
                            "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", target.toString()),
                        300,
                    )
                }
            }
            if (generation != own) return
            val input = withContext(Dispatchers.IO) { openStream(decoded).also { stream = it } }
            if (generation != own) return
            val created = withContext(Dispatchers.IO) {
                createLine(input.format).also { output = it; it.open(input.format) }
            }
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (generation != own) return
                check(scope.isActive) { "audioFailed" }
                line = created
                phase = targetPhase
                if (targetPhase == PlaybackPhase.PLAYING) created.start()
                val playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        val buffer = ByteArray(4096)
                        while (isActive && generation == own) {
                            if (phase == PlaybackPhase.PAUSED) { delay(20); continue }
                            val count = input.read(buffer)
                            if (count < 0 || !isActive || generation != own) break
                            level = SignalLevel.pcm16(buffer, count)
                            created.write(buffer, 0, count)
                        }
                        if (isActive && generation == own) created.drain()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Состояние и ресурсы освобождаются ниже. */ }
                }
                // Выполняется и если scope отменён до старта coroutine.
                playbackJob.invokeOnCompletion {
                    release()
                    synchronized(lifecycle) {
                        if (generation == own) { line = null; phase = PlaybackPhase.IDLE; level = 0f }
                    }
                }
                job = playbackJob
                published = true
                playbackJob.start()
            }
        } finally {
            if (!published) {
                release()
                synchronized(lifecycle) {
                    if (generation == own) { line = null; phase = PlaybackPhase.IDLE; level = 0f }
                }
            }
        }
    }

    override suspend fun pause() = synchronized(lifecycle) {
        check(phase == PlaybackPhase.PLAYING)
        try { line?.stop(); phase = PlaybackPhase.PAUSED; level = 0f }
        catch (error: Exception) { stopLocked(); throw error }
    }

    override suspend fun resume() = synchronized(lifecycle) {
        check(phase == PlaybackPhase.PAUSED)
        try { line?.start(); phase = PlaybackPhase.PLAYING }
        catch (error: Exception) { stopLocked(); throw error }
    }

    override fun playbackState(): PlaybackSessionState = synchronized(lifecycle) {
        val pos = runCatching {
            offset + (line?.longFramePosition ?: 0) / 16000.0 * rate
        }.getOrDefault(offset).coerceIn(0.0, duration.coerceAtLeast(0.0))
        PlaybackSessionState(
            phase = phase,
            sourceId = sourceId,
            positionSeconds = if (phase == PlaybackPhase.IDLE) 0.0 else pos,
            durationSeconds = duration,
            level = level.coerceIn(0f, 1f),
        )
    }

    override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState {
        val (before, compact, playbackRate) = synchronized(lifecycle) { Triple(playbackState(), compactSource, rate) }
        val target = before.seekTarget(positionSeconds) ?: error("audioFailed")
        val source = before.sourceId ?: error("audioFailed")
        startCapture(source, compact, target, playbackRate, before.phase)
        return playbackState()
    }

    override fun telemetry(): AudioTelemetry {
        val state = playbackState()
        return AudioTelemetry(state.phase.legacyValue, state.positionSeconds, state.durationSeconds, state.level)
    }

    override fun stop() = synchronized(lifecycle) { stopLocked() }

    private fun stopLocked() {
        generation++
        job?.cancel()
        job = null
        val old = line
        line = null
        phase = PlaybackPhase.IDLE
        level = 0f
        offset = 0.0
        runCatching { old?.stop() }
        runCatching { old?.close() }
    }
}
