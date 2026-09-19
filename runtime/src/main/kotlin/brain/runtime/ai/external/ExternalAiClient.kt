package brain.runtime.ai.external

import brain.ai.CloudTransport
import brain.ai.external.*
import brain.studio.*
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Транспорт внешнего AI; редиректы не могут перенести ключ на другого получателя. */
class ExternalAiClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(20)).build(),
) : CloudTransport {
    init { require(client.followRedirects() == HttpClient.Redirect.NEVER) { "cloudRedirectsForbidden" } }

    override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean {
        val response = send(builder(ExternalAiProtocol.models(connection, apiKey)).GET().build())
        response.body().close()
        return response.statusCode() in 200..299
    }

    override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
        val request = ExternalAiProtocol.generate(connection, role, apiKey, prompt)
        val response = send(builder(request).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(request.body!!)).build())
        return ExternalAiProtocol.text(connection.providerId, read(response))
    }

    override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String =
        transcribe(connection, apiKey, Path.of(file), language)

    suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: Path, language: String): String {
        val request = ExternalAiProtocol.audio(connection, apiKey)
        require(Files.isRegularFile(file) && Files.size(file) in 1..ExternalAiProtocol.MAX_AUDIO_BYTES) { "cloudAudioTooLarge" }
        val boundary = "Kasha-${UUID.randomUUID()}"
        val (name, type) = ExternalAiProtocol.audioType(file.toString())
        fun field(key: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n"
        val prefix = field("model", connection.modelFor(AiRole.SPEECH_TO_TEXT)!!) +
            (if (language.isNotBlank() && language != "system") field("language", language) else "") +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n"
        val publisher = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
            HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"))
        return ExternalAiProtocol.transcription(read(send(builder(request)
            .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(publisher).build())))
    }

    private fun builder(request: ExternalRequest) = HttpRequest.newBuilder(URI(request.url))
        .timeout(Duration.ofMinutes(5)).also { b -> request.headers.forEach { (key, value) -> b.header(key, value) } }

    private suspend fun send(request: HttpRequest): HttpResponse<java.io.InputStream> = suspendCancellableCoroutine { continuation ->
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        continuation.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, error ->
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(response) { _, value, _ -> value.body().close() }
        }
    }

    private suspend fun read(response: HttpResponse<java.io.InputStream>): String = withContext(Dispatchers.IO) {
        response.body().use { input ->
            check(response.statusCode() in 200..299) { "cloudHttp${response.statusCode()}" }
            val bytes = runInterruptible { input.readNBytes(ExternalAiProtocol.MAX_RESPONSE_BYTES + 1) }
            check(bytes.size <= ExternalAiProtocol.MAX_RESPONSE_BYTES) { "cloudResponseTooLarge" }
            bytes.toString(Charsets.UTF_8)
        }
    }
}
