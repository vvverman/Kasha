package brain.runtime

import brain.model.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
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
        assertEquals("hello", runner.run(listOf("/bin/echo", "hello"), 3).trim())
        assertFails { runner.run(listOf("/bin/sleep", "10"), 0) }
        val task = async { runner.run(listOf("/bin/sleep", "10"), 20) }
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

    private fun writePcmFixture(path: Path) {
        val rate = 16000
        val seconds = 4
        val samples = rate * seconds
        val pcm = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val t = index.toDouble() / rate
            val amplitude = if (t < 0.6 || t > 3.4) 0.3 * sin(2 * PI * 440 * t) else 0.0
            val sample = (amplitude * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[index * 2] = (sample and 0xff).toByte()
            pcm[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        val size = pcm.size
        val header = ByteArray(44)
        fun ascii(offset: Int, value: String) = value.encodeToByteArray().copyInto(header, offset)
        fun u16(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        fun u32(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
            header[offset + 2] = ((value ushr 16) and 0xff).toByte()
            header[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }
        ascii(0, "RIFF"); u32(4, 36 + size); ascii(8, "WAVE")
        ascii(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1)
        u32(24, rate); u32(28, rate * 2); u16(32, 2); u16(34, 16)
        ascii(36, "data"); u32(40, size)
        Files.write(path, header + pcm)
    }
}
