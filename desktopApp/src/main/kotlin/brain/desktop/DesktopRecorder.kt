package brain.desktop

import brain.domain.*
import brain.model.Capture
import brain.runtime.FileBrainStore
import brain.studio.SignalLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.*
import java.util.UUID
import javax.sound.sampled.*
import kotlin.concurrent.thread

/** Только Java Sound и файлы: последовательность пользовательских действий принадлежит Core. */
class DesktopRecorder(
    private val root: Path,
    private val store: FileBrainStore,
    private val enqueue: suspend (String) -> Unit,
) : RecorderSessionGateway, AutoCloseable {
    private val pending = root.resolve("pending")
    private val consentFile = root.resolve("microphone-consent")
    private val commands = Mutex()
    @Volatile private var state = RecorderSessionState()
    @Volatile private var running = false
    @Volatile private var signal = 0f
    private var input: TargetDataLine? = null
    private var worker: Thread? = null
    private var cancelledId: String? = null

    init { Files.createDirectories(pending) }

    private fun journals(): List<Path> = Files.list(pending).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".wav") }
            .sorted().toList()
    }
    private fun source(id: String): Path = journals().firstOrNull {
        it.fileName.toString().removeSuffix(".wav") == id
    } ?: error("audioFailed")

    override suspend fun permission(): RecorderPermission = withContext(Dispatchers.IO) {
        if (Files.exists(consentFile)) RecorderPermission.GRANTED else RecorderPermission.NOT_DETERMINED
    }
    override fun sessionState(): RecorderSessionState = state
    override fun level(): Float = if (state.phase == RecorderPhase.RECORDING) signal else 0f
    override suspend fun pendingRecordings(): List<PendingRecording> = commands.withLock {
        withContext(Dispatchers.IO) { readPending() }
    }
    private fun readPending(): List<PendingRecording> {
        val active = state.activeSessionId
        return journals().mapNotNull { path ->
            val id = path.fileName.toString().removeSuffix(".wav")
            when {
                id == active -> null
                Files.size(path) == 44L && runCatching { WavJournal.repair(path) == 0L }.getOrDefault(false) -> {
                    Files.deleteIfExists(path); null
                }
                else -> PendingRecording(id, Files.getLastModifiedTime(path).toMillis().coerceAtLeast(0))
            }
        }
    }

    override suspend fun start(): Unit = commands.withLock { withContext(Dispatchers.IO) {
        check(state.phase == RecorderPhase.IDLE && readPending().isEmpty()) { "audioFailed" }
        var line: TargetDataLine? = null
        for (rate in listOf(16000, 44100, 48000)) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            try { line = AudioSystem.getTargetDataLine(format); line.open(format); break }
            catch (_: LineUnavailableException) { line?.close(); line = null }
            catch (_: IllegalArgumentException) { line?.close(); line = null }
            catch (_: SecurityException) { line?.close(); Files.deleteIfExists(consentFile); error("audioFailed") }
        }
        val actual = line ?: error("audioFailed")
        val id = UUID.randomUUID().toString()
        var journal: WavJournal? = null
        try {
            val writer = WavJournal(pending.resolve("$id.wav"), actual.format.sampleRate.toInt())
            journal = writer
            input = actual; running = true; actual.start()
            state = RecorderSessionState(RecorderPhase.RECORDING, id)
            Files.writeString(consentFile, "yes")
            worker = thread(name = "Kasha-microphone", isDaemon = true) {
                var failure: Exception? = null
                try {
                    val buffer = ByteArray(2048)
                    var syncedAt = System.nanoTime()
                    while (running) {
                        if (state.phase == RecorderPhase.PAUSED) { Thread.sleep(25); continue }
                        val count = actual.read(buffer, 0, buffer.size)
                        if (count > 0 && state.phase == RecorderPhase.RECORDING) {
                            writer.append(buffer, count)
                            signal = SignalLevel.pcm16(buffer, count)
                        }
                        if (System.nanoTime() - syncedAt > 1_000_000_000) { writer.flush(); syncedAt = System.nanoTime() }
                    }
                } catch (e: Exception) { failure = e }
                finally {
                    running = false; signal = 0f; actual.close()
                    try { writer.close() } catch (e: Exception) { failure = e }
                    if (state.activeSessionId == id && state.phase != RecorderPhase.FINALIZING) {
                        state = RecorderSessionState(RecorderPhase.INTERRUPTED, id,
                            RecorderIssue(if (failure == null) RecorderIssueKind.INTERRUPTION else RecorderIssueKind.IO_FAILURE, false))
                    }
                }
            }
        } catch (e: Exception) {
            running = false; actual.close(); runCatching { journal?.close() }
            input = null; state = RecorderSessionState(issue = RecorderIssue(RecorderIssueKind.IO_FAILURE, false))
            throw e
        }
    } }

    override suspend fun pause(): Unit = commands.withLock { withContext(Dispatchers.IO) {
        check(state.phase == RecorderPhase.RECORDING) { "audioFailed" }
        input?.stop(); input?.flush(); signal = 0f
        state = state.copy(phase = RecorderPhase.PAUSED)
    } }
    override suspend fun resume(): Unit = commands.withLock { withContext(Dispatchers.IO) {
        check(state.phase == RecorderPhase.PAUSED && running) { "audioFailed" }
        input?.flush(); input?.start(); state = state.copy(phase = RecorderPhase.RECORDING)
    } }

    override suspend fun stopAndUpload(): Capture = commands.withLock { withContext(Dispatchers.IO) {
        val before = state
        check(before.phase in setOf(RecorderPhase.RECORDING, RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED)) { "audioFailed" }
        val id = before.activeSessionId ?: error("audioFailed")
        state = before.copy(phase = RecorderPhase.FINALIZING)
        try { stopInput(); accept(id) }
        finally { if (worker?.isAlive != true) state = RecorderSessionState() }
    } }

    override suspend fun cancelActive(sessionId: String): Unit = commands.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val before = state
            if (before.phase == RecorderPhase.IDLE && cancelledId == sessionId) return@withContext
            check(before.activeSessionId == sessionId && before.phase in
                setOf(RecorderPhase.RECORDING, RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED)) { "audioFailed" }
            state = before.copy(phase = RecorderPhase.FINALIZING)
            try {
                stopInput()
                Files.delete(source(sessionId))
                cancelledId = sessionId
            } finally { if (worker?.isAlive != true) state = RecorderSessionState() }
        }
    }

    override suspend fun recoverPending(pendingId: String): Capture = commands.withLock { withContext(Dispatchers.IO) {
        check(state.phase == RecorderPhase.IDLE) { "audioFailed" }
        source(pendingId)
        state = RecorderSessionState(RecorderPhase.FINALIZING, pendingId)
        try { accept(pendingId) } finally { if (worker?.isAlive != true) state = RecorderSessionState() }
    } }

    override suspend fun discardPending(pendingId: String): Unit = commands.withLock { withContext(Dispatchers.IO) {
        check(state.phase == RecorderPhase.IDLE) { "audioFailed" }
        Files.delete(source(pendingId))
    } }

    private suspend fun accept(id: String): Capture {
        val file = source(id)
        check(WavJournal.repair(file) > 0) { "audioFailed" }
        val capture = store.createCapture("original.wav", Files.readAllBytes(file), id)
        check(capture.id == id) { "audioFailed" }
        enqueue(capture.id)
        Files.delete(file)
        return capture
    }

    private fun stopInput() {
        running = false; input?.stop(); input?.close(); worker?.join(3000)
        check(worker?.isAlive != true) { "audioFailed" }
        worker = null; input = null; signal = 0f
    }

    override fun close() {
        val before = state
        if (before.activeSessionId != null) state = before.copy(phase = RecorderPhase.FINALIZING)
        stopInput()
        state = RecorderSessionState()
    }
}
