package brain.runtime

import brain.model.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.*
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class RuntimeTest {
    @Test fun rejectedDiskSaveDoesNotPublishMemoryState() = runBlocking {
        val root = Files.createTempDirectory("brain-write-failure")
        try {
            val store = FileBrainStore(root) { RuntimeStatus() }
            Files.createDirectory(root.resolve("brain.json"))
            Files.writeString(root.resolve("brain.json/nonempty"), "x")
            assertFails { store.createProject(ProjectDraft("Не сохранён")) }
            assertTrue(store.snapshot().projects.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun uploadRetryIsIdempotent() = runBlocking {
        val root = Files.createTempDirectory("brain-upload")
        try {
            val store = FileBrainStore(root) { RuntimeStatus() }; val id = UUID.randomUUID().toString()
            val first = store.createCapture("a.webm", byteArrayOf(1, 2), id)
            assertEquals(first, store.createCapture("a.webm", byteArrayOf(1, 2), id))
            assertFails { store.createCapture("a.webm", byteArrayOf(3), id) }
            assertEquals(1, store.snapshot().captures.size)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun restartMarksInterruptedCaptureButKeepsSource() = runBlocking {
        val root = Files.createTempDirectory("brain-restart")
        try {
            val s = FileBrainStore(root) { RuntimeStatus() }; val c = s.createCapture("a.wav", byteArrayOf(1))
            val reopened = FileBrainStore(root) { RuntimeStatus() }; val recovered = reopened.capture(c.id)!!
            assertEquals(CaptureStatus.FAILED, recovered.status); assertContentEquals(byteArrayOf(1), Files.readAllBytes(reopened.resolveAudio(recovered)))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun missingModelsNeverPretendToTranscribe() = runBlocking {
        val root = Files.createTempDirectory("brain-no-model")
        try {
            val s = FileBrainStore(root) { RuntimeStatus() }; val c = s.createCapture("a.wav", byteArrayOf(1))
            val result = LocalProcessing(s, emptyMap()).process(c.id)
            assertEquals(CaptureStatus.NEEDS_MODEL, result.status); assertEquals("", result.transcript)
            assertTrue(Files.exists(s.resolveAudio(result)))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun foreignWebOriginAndRebindingAreRejected() = testApplication {
        val root = Files.createTempDirectory("brain-origin")
        try {
            val s = FileBrainStore(root) { RuntimeStatus() }; application { brainModule(s, LocalProcessing(s, emptyMap())) }
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/snapshot") { header(HttpHeaders.Origin, "https://evil.example") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/snapshot") { header(HttpHeaders.Host, "evil.example:8787") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/projects") { contentType(ContentType.Application.Json); setBody("""{"title":"X"}""") }.status)
            assertEquals(HttpStatusCode.OK, client.post("/api/projects") { header("X-Kasha-Client", "web"); contentType(ContentType.Application.Json); setBody("""{"title":"X"}""") }.status)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun subprocessTimeoutAndCancellationWork() = runBlocking<Unit> {
        val runner = JvmCommandRunner()
        assertEquals("hello", runner.run(fixtureCommand("echo", "hello"), 10).trim())
        assertFailsWith<IllegalArgumentException> { runner.run(fixtureCommand("sleep", "10000"), 0) }
        val task = async { runner.run(fixtureCommand("sleep", "10000"), 20) }
        delay(100); task.cancel(); assertFailsWith<CancellationException> { task.await() }
    }
    @Test fun pcmCompactionKeepsOriginalBytes() = runBlocking {
        val root = Files.createTempDirectory("brain-pcm")
        try {
            val original = root.resolve("original.wav")
            writePcmFixture(original)
            val before = Files.readAllBytes(original)
            val (meta, spans) = PcmAudio.compact(original, root.resolve("compact.wav"))
            assertEquals(4.0, meta.duration, .01); assertTrue(spans.sumOf { it.duration } < 3)
            assertContentEquals(before, Files.readAllBytes(original)); assertTrue(PcmAudio.info(root.resolve("compact.wav")).duration < 3)
        } finally { root.toFile().deleteRecursively() }
    }

    /** Используем ту же JVM на всех ОС, без Unix-команд и зависимости от PATH. */
    private fun fixtureCommand(vararg args: String): List<String> {
        val bin = Path.of(System.getProperty("java.home"), "bin")
        val java = listOf("java", "java.exe").map(bin::resolve).first(Files::isRegularFile)
        val classpath = listOf(RuntimeCommandFixture::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        return listOf(java.toString(), "-cp", classpath, RuntimeCommandFixture::class.java.name) + args
    }

    /** Тот же сигнал: PCM16 mono/16kHz, 4 с, тон 440 Гц по краям и тишина в середине. */
    private fun writePcmFixture(path: Path) {
        val rate = 16_000
        val frames = rate * 4
        val bytes = frames * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
        repeat(frames) { index ->
            val time = index.toDouble() / rate
            val sample = if (time < 0.6 || time > 3.4) 0.3 * sin(2 * PI * 440 * time) else 0.0
            wav.putShort((sample * 32767).toInt().toShort())
        }
        Files.write(path, wav.array())
    }
}

/** Только тестовый дочерний процесс; в поставку приложения не входит. */
object RuntimeCommandFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.first()) {
            "echo" -> print(args[1])
            "sleep" -> Thread.sleep(args[1].toLong())
            else -> error("Неизвестная тестовая команда")
        }
    }
}
