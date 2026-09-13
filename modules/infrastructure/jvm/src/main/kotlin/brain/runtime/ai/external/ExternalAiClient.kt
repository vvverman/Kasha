package brain.runtime.ai.external

import brain.ai.connectors.AiHttpRequest
import brain.ai.connectors.AiHttpResponse
import brain.ai.connectors.AiHttpTransport
import brain.ai.connectors.ExternalAiProtocol
import brain.ai.connectors.HttpMethod
import brain.studio.AiRole
import brain.studio.CloudAiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** JVM infrastructure: только HTTP transport + чтение локального аудиофайла. Provider protocol общий в aiConnectors. */
class ExternalAiClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) {
    private val protocol = ExternalAiProtocol(AiHttpTransport(::execute))

    suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean =
        protocol.test(connection, apiKey)

    suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String =
        protocol.generate(connection, role, apiKey, prompt)

    suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: Path, language: String): String =
        protocol.transcribe(
            connection = connection,
            apiKey = apiKey,
            audio = withContext(Dispatchers.IO) { Files.readAllBytes(file) },
            fileName = file.fileName?.toString() ?: "audio.wav",
            language = language,
        )

    private suspend fun execute(request: AiHttpRequest): AiHttpResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI(request.url)).timeout(Duration.ofMinutes(30))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        when (request.method) {
            HttpMethod.GET -> builder.GET()
            HttpMethod.POST -> builder.POST(
                request.body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
            )
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        AiHttpResponse(response.statusCode(), response.body())
    }
}
