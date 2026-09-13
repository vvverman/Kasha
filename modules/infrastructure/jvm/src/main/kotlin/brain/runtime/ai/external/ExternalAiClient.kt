package brain.runtime.ai.external

import brain.studio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Единственное место JVM-runtime, где находятся реальные endpoint-ы внешнего inference. */
class ExternalAiClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        require(connection.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION) { "Не подтверждена передача данных внешнему ИИ" }
        val request = when (connection.providerId) {
            "openai" -> authorizedGet("https://api.openai.com/v1/models", apiKey)
            "anthropic" -> HttpRequest.newBuilder(URI("https://api.anthropic.com/v1/models"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(30)).GET().build()
            "gemini" -> HttpRequest.newBuilder(URI("https://generativelanguage.googleapis.com/v1beta/models"))
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(30)).GET().build()
            "openrouter" -> authorizedGet("https://openrouter.ai/api/v1/models", apiKey)
            "openai-compatible", "custom" -> authorizedGet(join(connection.endpointRequired(), "models"), apiKey)
            else -> error("Неизвестный AI provider")
        }
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    }

    suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        require(connection.enabled && connection.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION)
        require(role != AiRole.SPEECH_TO_TEXT)
        val model = connection.modelFor(role) ?: error("Для роли $role не выбрана модель")
        when (connection.providerId) {
            "openai" -> openAiResponses(model, apiKey, prompt)
            "anthropic" -> anthropic(model, apiKey, prompt)
            "gemini" -> gemini(model, apiKey, prompt)
            "openrouter" -> chatCompletions("https://openrouter.ai/api/v1/chat/completions", model, apiKey, prompt)
            "openai-compatible", "custom" -> chatCompletions(join(connection.endpointRequired(), "chat/completions"), model, apiKey, prompt)
            else -> error("Неизвестный AI provider")
        }
    }

    suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: Path, language: String): String = withContext(Dispatchers.IO) {
        require(connection.enabled && connection.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION)
        val model = connection.modelFor(AiRole.SPEECH_TO_TEXT) ?: error("Не выбрана модель транскрибации")
        val endpoint = when (connection.providerId) {
            "openai" -> "https://api.openai.com/v1/audio/transcriptions"
            "custom" -> join(connection.endpointRequired(), "audio/transcriptions")
            else -> error("${connection.providerId} не поддерживает внешний STT adapter")
        }
        val boundary = "Kasha-${UUID.randomUUID()}"
        val bytes = multipartAudio(boundary, model, file, language)
        val request = HttpRequest.newBuilder(URI(endpoint))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofMinutes(30))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        requireSuccess(response.statusCode())
        json.parseToJsonElement(response.body()).jsonObject["text"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotBlank) ?: error("Провайдер не вернул транскрипцию")
    }

    private fun openAiResponses(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
        }.toString()
        val response = client.send(
            HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        requireSuccess(response.statusCode())
        val root = json.parseToJsonElement(response.body()).jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        val output = root["output"]?.jsonArray.orEmpty()
        return output.asSequence()
            .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n").trim().takeIf(String::isNotBlank)
            ?: error("OpenAI не вернул текст")
    }

    private fun anthropic(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
        }.toString()
        val response = client.send(
            HttpRequest.newBuilder(URI("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        requireSuccess(response.statusCode())
        return json.parseToJsonElement(response.body()).jsonObject["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("Anthropic не вернул текст")
    }

    private fun gemini(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
        }.toString()
        val response = client.send(
            HttpRequest.newBuilder(URI("https://generativelanguage.googleapis.com/v1beta/models/${encodePath(model)}:generateContent"))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        requireSuccess(response.statusCode())
        return json.parseToJsonElement(response.body()).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("Gemini не вернул текст")
    }

    private fun chatCompletions(endpoint: String, model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
        }.toString()
        val response = client.send(
            HttpRequest.newBuilder(URI(endpoint))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        requireSuccess(response.statusCode())
        return json.parseToJsonElement(response.body()).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank) ?: error("Внешний API не вернул текст")
    }

    private fun authorizedGet(url: String, apiKey: String): HttpRequest = HttpRequest.newBuilder(URI(url))
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofSeconds(30)).GET().build()

    private fun CloudAiConnection.endpointRequired(): String = endpoint?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://127.0.0.1") || it.startsWith("http://localhost") }
        ?: error("Нужен HTTPS endpoint (или localhost для локального сервера)")

    private fun join(base: String, suffix: String): String {
        val b = base.trimEnd('/')
        if (b.endsWith("/$suffix")) return b
        return "$b/$suffix"
    }

    private fun requireSuccess(status: Int) {
        require(status in 200..299) { "Внешний AI вернул HTTP $status" }
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20").replace("%2F", "/")

    private fun multipartAudio(boundary: String, model: String, file: Path, language: String): ByteArray {
        val line = "\r\n"
        val header = buildString {
            append("--$boundary$line")
            append("Content-Disposition: form-data; name=\"model\"$line$line$model$line")
            if (language.isNotBlank() && language != "system") {
                append("--$boundary$line")
                append("Content-Disposition: form-data; name=\"language\"$line$line$language$line")
            }
            append("--$boundary$line")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$line")
            append("Content-Type: audio/wav$line$line")
        }.toByteArray()
        val footer = "$line--$boundary--$line".toByteArray()
        val audio = Files.readAllBytes(file)
        return ByteArray(header.size + audio.size + footer.size).also { out ->
            header.copyInto(out, 0)
            audio.copyInto(out, header.size)
            footer.copyInto(out, header.size + audio.size)
        }
    }
}
