package brain.runtime

import brain.model.*
import brain.runtime.ai.*
import brain.studio.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

private class LocalAccessDenied : IllegalArgumentException("Local access only")
private const val MAX_AUDIO = 64L * 1024 * 1024
private val localOrigins = setOf("http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:8787", "http://127.0.0.1:8787")

/** Локальный HTTP — транспорт между web UI и процессом на том же устройстве, не сетевой backend. */
private val LocalAccess = createApplicationPlugin("LocalAccess") {
    onCall { call ->
        val host = call.request.headers[HttpHeaders.Host]?.lowercase()?.substringBefore(':')
        if (host != null && host !in setOf("localhost", "127.0.0.1")) throw LocalAccessDenied()
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin != null && origin !in localOrigins) throw LocalAccessDenied()
        if (call.request.headers["Sec-Fetch-Site"] == "cross-site" && origin == null) throw LocalAccessDenied()
        if (call.request.httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete) && call.request.headers["X-Kasha-Client"] != "web") throw LocalAccessDenied()
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(length == null || length <= MAX_AUDIO + 65536)
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Cache-Control", "no-store")
    }
}

fun main() {
    val root = Path.of(System.getenv("KASHA_HOME") ?: Path.of(System.getProperty("user.home"), ".kasha-studio-test").toString())
    val simulated = System.getenv("KASHA_DEMO_AI") == "1"
    val env = System.getenv(); val ffmpeg = env["KASHA_FFMPEG"] ?: "ffmpeg"
    val prefs = PreferenceStore(root)
    val store = FileBrainStore(root, runtimeStatus = { RuntimeStatus(localOnly = true, simulated = simulated) }, singleCurrent = true)
    val legacy = LocalProcessing(store, env)
    val bundledModels = buildMap<String, Path> {
        env["KASHA_WHISPER_MODEL"]?.let { Path.of(it) }?.takeIf(Files::isRegularFile)?.let { put(AiCatalog.DEFAULT_STT, it) }
        env["KASHA_LLAMA_MODEL"]?.let { Path.of(it) }?.takeIf(Files::isRegularFile)?.let { put(AiCatalog.DEFAULT_TEXT, it) }
    }
    val packages = JvmAiPackageGateway(root, bundledModels)
    val cloud = JvmCloudAiGateway(root)
    val intelligence: Intelligence = if (simulated) DemoIntelligence() else RoutedStudioIntelligence(prefs, env, root, packages, cloud)
    val processor = StudioProcessor(store, prefs, intelligence, ffmpeg)
    val baseRepository = StudioDiskRepository(store, processor, prefs, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val studio: StudioRepository = AiStudioRepository(baseRepository, packages, cloud)
    val webRoot = Path.of(System.getenv("KASHA_WEB_ROOT") ?: "composeApp/build/dist/wasmJs/productionExecutable")
    println("Kasha: http://127.0.0.1:8787 ; simulated AI=$simulated ; local-first=true")
    embeddedServer(Netty, host = "127.0.0.1", port = 8787) {
        brainModule(store, legacy, webRoot, studio)
    }.start(wait = true)
}

fun Application.brainModule(store: FileBrainStore, processing: LocalProcessing, webRoot: Path? = null, studio: StudioRepository? = null) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(StatusPages) {
        exception<LocalAccessDenied> { call, _ -> call.respond(HttpStatusCode.Forbidden, ApiError("Local access only")) }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val expected = cause is IllegalArgumentException || cause is IllegalStateException
            call.respond(if (expected) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError,
                ApiError(if (expected) cause.message ?: "Invalid request" else "Local service error"))
        }
    }
    install(LocalAccess)
    install(CORS) {
        allowHost("localhost:8080"); allowHost("127.0.0.1:8080"); allowHost("localhost:8787"); allowHost("127.0.0.1:8787")
        allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType); allowHeader("X-Kasha-Client"); allowHeader("X-Capture-Id"); allowNonSimpleContentTypes = true
    }
    routing {
        get("/api/health") { call.respond(mapOf("ok" to true, "localOnly" to true)) }
        get("/api/snapshot") { call.respond(store.snapshot()) }
        get("/api/preferences") { call.respond(studio?.preferences() ?: Preferences()) }
        put("/api/preferences") { check(studio != null); studio.savePreferences(call.receive<Preferences>()); call.respond(studio.preferences()) }
        post("/api/demo") { check(studio != null); call.respond(HttpStatusCode.Created, studio.createDemo()) }
        aiRoutes(studio)

        post("/api/projects") { call.respond(store.createProject(call.receive<ProjectDraft>())) }
        put("/api/projects/{id}") { call.respond(store.updateProject(call.parameters["id"]!!, call.receive<ProjectUpdate>())) }
        post("/api/projects/{id}/pin") { call.respond(store.pinProject(call.parameters["id"]!!, call.receive<PinRequest>().pinned)) }
        post("/api/pins/order") { call.respond(store.orderPins(call.receive<PinOrderRequest>().ids)) }
        post("/api/projects/order") { call.respond(store.orderProjects(call.receive<OrderRequest>().ids)) }
        post("/api/projects/{id}/notes/order") { call.respond(store.orderNotes(call.parameters["id"]!!, call.receive<OrderRequest>().ids)) }

        put("/api/notes/{id}") { call.respond(store.updateNote(call.parameters["id"]!!, call.receive<NoteUpdate>())) }
        post("/api/notes/{id}/pin") { call.respond(store.pinNote(call.parameters["id"]!!, call.receive<PinRequest>().pinned)) }

        put("/api/tasks/{id}") { call.respond(store.updateTask(call.parameters["id"]!!, call.receive<TaskUpdate>())) }
        put("/api/tasks/{id}/schedule") { call.respond(store.rescheduleTask(call.parameters["id"]!!, call.receive<TaskScheduleUpdate>())) }
        post("/api/tasks/{id}/complete") { call.respond(store.completeTask(call.parameters["id"]!!)) }
        delete("/api/tasks/{id}") { store.deleteTask(call.parameters["id"]!!); call.respond(mapOf("ok" to true)) }
        post("/api/tasks/order") { call.respond(store.orderTasks(call.receive<OrderRequest>().ids)) }

        post("/api/captures/audio") {
            var fileName = "capture.webm"; var bytes: ByteArray? = null
            call.receiveMultipart(formFieldLimit = MAX_AUDIO).forEachPart { part ->
                try {
                    if (part is PartData.FileItem) {
                        require(bytes == null); fileName = part.originalFileName ?: fileName
                        bytes = part.provider().readRemaining(MAX_AUDIO + 1).readByteArray(); require(bytes!!.size <= MAX_AUDIO)
                    }
                } finally { part.dispose() }
            }
            val c = store.createCapture(fileName, bytes ?: error("No audio"), call.request.headers["X-Capture-Id"])
            if (c.status == CaptureStatus.QUEUED) {
                if (studio != null) studio.reprocess(c.id) else processing.enqueue(c.id, this@brainModule)
            }
            call.respond(HttpStatusCode.Created, store.capture(c.id)!!)
        }
        get("/api/captures/{id}/audio") {
            val c = store.capture(call.parameters["id"]!!) ?: error("No source")
            call.respondFile(store.resolveAudio(c, call.request.queryParameters["compact"] == "true").toFile())
        }
        put("/api/captures/{id}/draft") { call.respond(store.updateDraft(call.parameters["id"]!!, call.receive<CaptureDraftUpdate>())) }
        post("/api/captures/{id}/process") {
            val id = call.parameters["id"]!!; call.respond(studio?.reprocess(id) ?: processing.enqueue(id, this@brainModule))
        }
        post("/api/captures/{id}/tidy") { check(studio != null); call.respond(studio.tidy(call.parameters["id"]!!)) }
        post("/api/captures/{id}/rank") { check(studio != null); call.respond(studio.rank(call.parameters["id"]!!)) }
        delete("/api/captures/{id}") { check(studio != null); studio.discard(call.parameters["id"]!!); call.respond(mapOf("ok" to true)) }
        post("/api/captures/{id}/distribute") { call.respond(store.distribute(call.parameters["id"]!!, call.receive<DistributionRequest>())) }
        post("/api/captures/{id}/task") { call.respond(store.distributeTask(call.parameters["id"]!!, call.receive<TaskDistributionRequest>())) }

        if (webRoot != null) staticFiles("/", webRoot.toFile())
    }
}
