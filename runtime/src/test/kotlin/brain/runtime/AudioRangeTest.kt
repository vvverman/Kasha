package brain.runtime

import brain.model.RuntimeStatus
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*

/** ТЗ 5.1: настоящий аудиомаршрут, диапазоны для перемотки и прежняя local-only граница. */
class AudioRangeTest {
    @Test fun audioRangesReturnTheExactBytesWithoutChangingTheSource() = testApplication {
        val root = Files.createTempDirectory("kasha-audio-range-")
        try {
            val store = FileBrainStore(root, runtimeStatus = { RuntimeStatus() })
            val bytes = ByteArray(1024) { (it % 251).toByte() }
            val capture = store.createCapture("source.wav", bytes)
            val path = "/api/captures/${capture.id}/audio"
            application { brainModule(store, LocalProcessing(store, emptyMap())) }
            val whole = client.get(path)
            assertEquals(HttpStatusCode.OK, whole.status)
            assertContentEquals(bytes, whole.body<ByteArray>())
            for ((range, first, last) in listOf(
                Triple("bytes=0-43", 0, 43),
                Triple("bytes=250-499", 250, 499),
                Triple("bytes=900-", 900, 1023),
                Triple("bytes=-24", 1000, 1023),
            )) {
                val response = client.get(path) { header(HttpHeaders.Range, range) }
                assertEquals(HttpStatusCode.PartialContent, response.status, range)
                assertEquals("bytes $first-$last/${bytes.size}", response.headers[HttpHeaders.ContentRange])
                assertContentEquals(bytes.copyOfRange(first, last + 1), response.body<ByteArray>())
            }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,
                client.get(path) { header(HttpHeaders.Range, "bytes=2048-") }.status)
            assertEquals(HttpStatusCode.Forbidden,
                client.get(path) { header(HttpHeaders.Range, "bytes=0-43"); header(HttpHeaders.Origin, "https://outside.invalid") }.status)
            assertContentEquals(bytes, Files.readAllBytes(store.resolveAudio(capture)))
        } finally { root.toFile().deleteRecursively() }
    }
}
