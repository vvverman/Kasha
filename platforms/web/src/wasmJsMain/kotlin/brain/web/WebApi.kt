package brain.web

import brain.model.*
import brain.studio.*
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Web UI talks only to the Kasha process on this device. */
class WebBrainRepository(private val baseUrl: String) : StudioRepository, AiPlatformServices {
    override var simulated: Boolean = true; private set
    private val client = HttpClient(Js) {
        expectSuccess = true
        defaultRequest { header("X-Kasha-Client", "web") }
        install(HttpTimeout) { requestTimeoutMillis = 30000 }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    }

    override val aiPackages: AiPackageGateway = object : AiPackageGateway {
        override val available = true
        override suspend fun states(): List<AiPackageState> = client.get("$baseUrl/api/ai/models").body()
        override suspend fun install(engineId: String) {
            client.post("$baseUrl/api/ai/models/$engineId/install")
        }
        override suspend fun remove(engineId: String) {
            client.delete("$baseUrl/api/ai/models/$engineId")
        }
    }

    override val cloudAi: CloudAiGateway = object : CloudAiGateway {
        override val available = true
        override suspend fun connections(): List<CloudAiConnection> = client.get("$baseUrl/api/ai/cloud").body()
        override suspend fun save(connection: CloudAiConnection, apiKey: String?) {
            client.put("$baseUrl/api/ai/cloud/${connection.providerId}") {
                contentType(ContentType.Application.Json)
                setBody(CloudAiConnectionRequest(connection, apiKey))
            }
        }
        override suspend fun remove(providerId: String) {
            client.delete("$baseUrl/api/ai/cloud/$providerId")
        }
        override suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean =
            client.post("$baseUrl/api/ai/cloud/${connection.providerId}/test") {
                contentType(ContentType.Application.Json)
                setBody(CloudAiConnectionRequest(connection, apiKey))
            }.body<CloudAiConnectionTestResult>().ok
    }

    override suspend fun snapshot(): AppSnapshot = client.get("$baseUrl/api/snapshot").body<AppSnapshot>().also { simulated = it.runtime.simulated }
    override suspend fun preferences(): Preferences = client.get("$baseUrl/api/preferences").body()
    override suspend fun savePreferences(value: Preferences) { client.put("$baseUrl/api/preferences") { contentType(ContentType.Application.Json); setBody(value) } }
    override suspend fun createDemo(): Capture = client.post("$baseUrl/api/demo").body()
    override suspend fun createProject(draft: ProjectDraft): Project = client.post("$baseUrl/api/projects") { contentType(ContentType.Application.Json); setBody(draft) }.body()
    override suspend fun updateProject(id: String, update: ProjectUpdate): Project = client.put("$baseUrl/api/projects/$id") { contentType(ContentType.Application.Json); setBody(update) }.body()
    override suspend fun pinProject(id: String, pinned: Boolean): Project = client.post("$baseUrl/api/projects/$id/pin") { contentType(ContentType.Application.Json); setBody(PinRequest(pinned)) }.body()
    override suspend fun orderPins(ids: List<String>) { client.post("$baseUrl/api/pins/order") { contentType(ContentType.Application.Json); setBody(PinOrderRequest(ids)) } }
    override suspend fun orderProjects(ids: List<String>) { client.post("$baseUrl/api/projects/order") { contentType(ContentType.Application.Json); setBody(OrderRequest(ids)) } }
    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture = client.put("$baseUrl/api/captures/$id/draft") { contentType(ContentType.Application.Json); setBody(update) }.body()
    override suspend fun distribute(id: String, request: DistributionRequest): Note = client.post("$baseUrl/api/captures/$id/distribute") { contentType(ContentType.Application.Json); setBody(request) }.body()
    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = client.post("$baseUrl/api/captures/$id/task") { contentType(ContentType.Application.Json); setBody(request) }.body()
    override suspend fun updateNote(id: String, update: NoteUpdate): Note = client.put("$baseUrl/api/notes/$id") { contentType(ContentType.Application.Json); setBody(update) }.body()
    override suspend fun pinNote(id: String, pinned: Boolean): Note = client.post("$baseUrl/api/notes/$id/pin") { contentType(ContentType.Application.Json); setBody(PinRequest(pinned)) }.body()
    override suspend fun orderNotes(projectId: String, ids: List<String>) { client.post("$baseUrl/api/projects/$projectId/notes/order") { contentType(ContentType.Application.Json); setBody(OrderRequest(ids)) } }
    override suspend fun updateTask(id: String, update: TaskUpdate): Task = client.put("$baseUrl/api/tasks/$id") { contentType(ContentType.Application.Json); setBody(update) }.body()
    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task = client.put("$baseUrl/api/tasks/$id/schedule") { contentType(ContentType.Application.Json); setBody(update) }.body()
    override suspend fun completeTask(id: String): Task = client.post("$baseUrl/api/tasks/$id/complete").body()
    override suspend fun deleteTask(id: String) { client.delete("$baseUrl/api/tasks/$id") }
    override suspend fun orderTasks(ids: List<String>) { client.post("$baseUrl/api/tasks/order") { contentType(ContentType.Application.Json); setBody(OrderRequest(ids)) } }
    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> = emptyList()
    override suspend fun reprocess(id: String): Capture = client.post("$baseUrl/api/captures/$id/process").body()
    override suspend fun tidy(id: String): Capture = client.post("$baseUrl/api/captures/$id/tidy").body()
    override suspend fun rank(id: String): Capture = client.post("$baseUrl/api/captures/$id/rank").body()
    override suspend fun discard(id: String) { client.delete("$baseUrl/api/captures/$id") }
}
