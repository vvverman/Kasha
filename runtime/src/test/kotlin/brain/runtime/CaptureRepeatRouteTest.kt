package brain.runtime

import brain.model.*
import brain.studio.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class CaptureRepeatRouteTest {
    @Test fun repeatUsesDedicatedSttCommandAndKeepsLocalAccessProtection() = testApplication {
        val root = Files.createTempDirectory("kasha-repeat-route-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FileBrainStore(root) { RuntimeStatus(simulated = true) }
            val capture = store.createCapture("test.wav", byteArrayOf(1))
            val preferences = PreferenceStore(root)
            val base = StudioDiskRepository(store,
                StudioProcessor(store, preferences, DemoIntelligence(0), "unused"), preferences, scope)
            val calls = mutableListOf<String>()
            val repository = object : StudioRepository by base {
                override suspend fun retranscribe(id: String): Capture {
                    assertEquals(capture.id, id)
                    calls += "retranscribe"
                    return capture.copy(transcript = "Новая транскрибация")
                }
                override suspend fun reprocess(id: String): Capture {
                    calls += "reprocess"
                    return capture
                }
                override suspend fun tidy(id: String): Capture {
                    calls += "tidy"
                    return capture.copy(preparedText = "Нормализация")
                }
            }
            application { brainModule(store, LocalProcessing(store, emptyMap()), studio = repository) }
            val path = "/api/captures/${capture.id}/retranscribe"
            assertEquals(HttpStatusCode.Forbidden, client.post(path).status)
            assertEquals(HttpStatusCode.Forbidden, client.post(path) {
                header("X-Kasha-Client", "web")
                header(HttpHeaders.Origin, "https://foreign.example")
            }.status)
            assertTrue(calls.isEmpty())
            val repeated = client.post(path) { header("X-Kasha-Client", "web") }
            assertEquals(HttpStatusCode.OK, repeated.status)
            val result = Json.decodeFromString<Capture>(repeated.bodyAsText())
            assertEquals("Новая транскрибация", result.transcript)
            assertEquals(listOf("retranscribe"), calls)
            assertEquals(HttpStatusCode.OK, client.post("/api/captures/${capture.id}/tidy") {
                header("X-Kasha-Client", "web")
            }.status)
            assertEquals(listOf("retranscribe", "tidy"), calls)
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    @Test fun repeatWithoutStudioDoesNotFallBackToLegacyRecovery() = testApplication {
        val root = Files.createTempDirectory("kasha-repeat-no-studio-")
        try {
            val store = FileBrainStore(root) { RuntimeStatus() }
            val capture = store.createCapture("test.wav", byteArrayOf(1))
            application { brainModule(store, LocalProcessing(store, emptyMap())) }
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/captures/${capture.id}/retranscribe") {
                header("X-Kasha-Client", "web")
            }.status)
            assertEquals(capture, store.capture(capture.id))
        } finally { root.toFile().deleteRecursively() }
    }
}
