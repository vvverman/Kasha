package brain.runtime

import brain.model.RuntimeStatus
import brain.runtime.ai.AiStudioRepository
import brain.studio.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class AiCapabilityRouteTest {
    @Test fun webReceivesSameRoleStatusThroughProtectedLocalTransport() = testApplication {
        val root = Files.createTempDirectory("kasha-capability-route-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FileBrainStore(root) { RuntimeStatus() }
            val preferences = PreferenceStore(root)
            val processor = StudioProcessor(store, preferences, DemoIntelligence(), "unused")
            val base = StudioDiskRepository(store, processor, preferences, scope)
            val repository = AiStudioRepository(base, NoopAiPackageGateway, NoopCloudAiGateway) { false }
            application { brainModule(store, LocalProcessing(store, emptyMap()), studio = repository) }
            val selection = AiSelection("native.apple.speech", "cloud:openai:TEXT", "local.unknown")
            val response = client.post("/api/ai/capabilities") {
                header("X-Kasha-Client", "web")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(selection))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val actual = Json.decodeFromString<List<AiRoleCapability>>(response.bodyAsText())
            assertEquals(repository.aiExecution.roles(selection), actual)
            assertEquals(AiSelection(), preferences.read().ai)
            assertTrue(actual.all { !it.executable })
            assertFalse(Files.exists(root.resolve("ai/connections.json")))
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/ai/capabilities") {
                header(HttpHeaders.Origin, "https://foreign.example")
                header("X-Kasha-Client", "web")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(selection))
            }.status)
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }
}
