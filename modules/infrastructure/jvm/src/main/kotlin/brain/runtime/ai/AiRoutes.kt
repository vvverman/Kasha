package brain.runtime.ai

import brain.studio.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** Только localhost transport; внешние API-ключи никогда не возвращаются клиенту. */
fun Route.aiRoutes(studio: StudioRepository?) {
    val services = studio as? AiPlatformServices

    get("/api/ai/models") {
        check(services != null)
        call.respond(services.aiPackages.states())
    }
    post("/api/ai/models/{id}/install") {
        check(services != null)
        val id = call.parameters["id"] ?: error("Missing model id")
        services.aiPackages.install(id)
        call.respond(services.aiPackages.states())
    }
    delete("/api/ai/models/{id}") {
        check(services != null)
        val id = call.parameters["id"] ?: error("Missing model id")
        services.aiPackages.remove(id)
        call.respond(services.aiPackages.states())
    }

    get("/api/ai/cloud") {
        check(services != null)
        call.respond(services.cloudAi.connections())
    }
    put("/api/ai/cloud/{providerId}") {
        check(services != null)
        val providerId = call.parameters["providerId"] ?: error("Missing provider")
        val request = call.receive<CloudAiConnectionRequest>()
        require(request.connection.providerId == providerId)
        services.cloudAi.save(request.connection, request.apiKey)
        call.respond(HttpStatusCode.OK, services.cloudAi.connections())
    }
    post("/api/ai/cloud/{providerId}/test") {
        check(services != null)
        val providerId = call.parameters["providerId"] ?: error("Missing provider")
        val request = call.receive<CloudAiConnectionRequest>()
        require(request.connection.providerId == providerId)
        call.respond(CloudAiConnectionTestResult(services.cloudAi.test(request.connection, request.apiKey)))
    }
    delete("/api/ai/cloud/{providerId}") {
        check(services != null)
        val providerId = call.parameters["providerId"] ?: error("Missing provider")
        services.cloudAi.remove(providerId)
        call.respond(services.cloudAi.connections())
    }
}
