package brain.runtime

import brain.model.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import java.nio.file.*
import java.util.UUID
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
            val runner = JvmCommandRunner()
            runner.run(listOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "aevalsrc=if(lt(t\\,0.6)+gt(t\\,3.4)\\,0.3*sin(2*PI*440*t)\\,0):s=16000:d=4", "-ac", "1", "-c:a", "pcm_s16le", original.toString()), 20)
            val before = Files.readAllBytes(original)
            val (meta, spans) = PcmAudio.compact(original, root.resolve("compact.wav"))
            assertEquals(4.0, meta.duration, .01); assertTrue(spans.sumOf { it.duration } < 3)
            assertContentEquals(before, Files.readAllBytes(original)); assertTrue(PcmAudio.info(root.resolve("compact.wav")).duration < 3)
        } finally { root.toFile().deleteRecursively() }
    }
}
