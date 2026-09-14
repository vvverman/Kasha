package brain.ai.connectors

import brain.studio.AiPrivacy
import brain.studio.AiRole
import brain.studio.CloudAiConnection
import kotlinx.serialization.json.*
import kotlin.random.Random

enum class HttpMethod { GET, POST }

data class AiHttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class AiHttpResponse(val status: Int, val body: String = "")

/** Единственная platform-зависимая часть внешнего AI: выполнить уже сформированный HTTP-запрос. */
fun interface AiHttpTransport {
    suspend fun execute(request: AiHttpRequest): AiHttpResponse
}

/**
 * Общий протокол внешних AI Kasha. Здесь живут provider endpoints, request/response schemas
 * и privacy checks. API key приходит только как transient parameter и нигде не сохраняется.
 */
class ExternalAiProtocol(private val transport: AiHttpTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean {
        requireConsent(connection)
        val request = when (connection.providerId) {
            "openai" -> bearerGet("https://api.openai.com/v1/models", apiKey)
            "anthropic" -> AiHttpRequest(
                HttpMethod.GET,
                "https://api.anthropic.com/v1/models",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            )
            "gemini" -> AiHttpRequest(
                HttpMethod.GET,
                "https://generativelanguage.googleapis.com/v1beta/models",
                mapOf("x-goog-api-key" to apiKey),
            )
            "openrouter" -> bearerGet("https://openrouter.ai/api/v1/models", apiKey)
            "openai-compatible", "custom" -> bearerGet(join(connection.endpointRequired(), "models"), apiKey)
            else -> error("Неизвестный AI provider: ${connection.providerId}")
        }
        return transport.execute(request).status in 200..299
    }

    suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
        requireConsent(connection)
        require(connection.enabled) { "Внешний AI выключен" }
        require(role != AiRole.SPEECH_TO_TEXT)
        val model = connection.modelFor(role) ?: error("Для роли $role не выбрана модель")
        val request = when (connection.providerId) {
            "openai" -> jsonPost(
                "https://api.openai.com/v1/responses", apiKey,
                buildJsonObject { put("model", model); put("input", prompt) }.toString(),
            )
            "anthropic" -> AiHttpRequest(
                HttpMethod.POST,
                "https://api.anthropic.com/v1/messages",
                mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json",
                ),
                buildJsonObject {
                    put("model", model)
                    put("max_tokens", 4096)
                    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
                }.toString().encodeToByteArray(),
            )
            "gemini" -> AiHttpRequest(
                HttpMethod.POST,
                "https://generativelanguage.googleapis.com/v1beta/models/${model.pathSafe()}:generateContent",
                mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
                buildJsonObject {
                    putJsonArray("contents") {
                        add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } })
                    }
                }.toString().encodeToByteArray(),
            )
            "openrouter" -> chatRequest("https://openrouter.ai/api/v1/chat/completions", model, apiKey, prompt)
            "openai-compatible", "custom" -> chatRequest(join(connection.endpointRequired(), "chat/completions"), model, apiKey, prompt)
            else -> error("Неизвестный AI provider: ${connection.providerId}")
        }
        val response = transport.execute(request).requireSuccess()
        return parseGeneratedText(connection.providerId, response.body)
    }

    suspend fun transcribe(
        connection: CloudAiConnection,
        apiKey: String,
        audio: ByteArray,
        fileName: String = "audio.wav",
        language: String = "",
    ): String {
        requireConsent(connection)
        require(connection.enabled) { "Внешний AI выключен" }
        val model = connection.modelFor(AiRole.SPEECH_TO_TEXT) ?: error("Не выбрана модель транскрибации")
        val endpoint = when (connection.providerId) {
            "openai" -> "https://api.openai.com/v1/audio/transcriptions"
            "custom" -> join(connection.endpointRequired(), "audio/transcriptions")
            else -> error("${connection.providerId} не поддерживает внешний STT adapter")
        }
        val boundary = "Kasha-${Random.nextLong().toString(16)}"
        val response = transport.execute(
            AiHttpRequest(
                HttpMethod.POST,
                endpoint,
                mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                ),
                multipartAudio(boundary, model, fileName, audio, language),
            ),
        ).requireSuccess()
        return json.parseToJsonElement(response.body).jsonObject["text"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotBlank) ?: error("Провайдер не вернул транскрипцию")
    }

    private fun parseGeneratedText(provider: String, raw: String): String = when (provider) {
        "openai" -> {
            val root = json.parseToJsonElement(raw).jsonObject
            root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: root["output"]?.jsonArray.orEmpty().asSequence()
                    .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n").trim().takeIf(String::isNotBlank)
                ?: error("OpenAI не вернул текст")
        }
        "anthropic" -> json.parseToJsonElement(raw).jsonObject["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("Anthropic не вернул текст")
        "gemini" -> json.parseToJsonElement(raw).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("Gemini не вернул текст")
        else -> json.parseToJsonElement(raw).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank) ?: error("Внешний API не вернул текст")
    }

    private fun chatRequest(url: String, model: String, apiKey: String, prompt: String) = jsonPost(
        url,
        apiKey,
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
        }.toString(),
    )

    private fun jsonPost(url: String, apiKey: String, body: String) = AiHttpRequest(
        HttpMethod.POST,
        url,
        mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
        body.encodeToByteArray(),
    )

    private fun bearerGet(url: String, apiKey: String) = AiHttpRequest(
        HttpMethod.GET,
        url,
        mapOf("Authorization" to "Bearer $apiKey"),
    )

    private fun requireConsent(connection: CloudAiConnection) {
        require(connection.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION) {
            "Не подтверждена передача данных внешнему ИИ"
        }
    }

    private fun CloudAiConnection.endpointRequired(): String = endpoint?.trim()?.takeIf {
        it.startsWith("https://") || it.startsWith("http://127.0.0.1") || it.startsWith("http://localhost")
    } ?: error("Нужен HTTPS endpoint (или localhost для локального сервера)")

    private fun join(base: String, suffix: String): String {
        val clean = base.trimEnd('/')
        return if (clean.endsWith("/$suffix")) clean else "$clean/$suffix"
    }

    private fun AiHttpResponse.requireSuccess(): AiHttpResponse {
        require(status in 200..299) { "Внешний AI вернул HTTP $status" }
        return this
    }

    private fun String.pathSafe(): String = replace(" ", "%20")

    private fun multipartAudio(
        boundary: String,
        model: String,
        fileName: String,
        audio: ByteArray,
        language: String,
    ): ByteArray {
        val crlf = "\r\n"
        val head = buildString {
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"model\"$crlf$crlf$model$crlf")
            if (language.isNotBlank() && language != "system") {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"language\"$crlf$crlf$language$crlf")
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$crlf")
            append("Content-Type: application/octet-stream$crlf$crlf")
        }.encodeToByteArray()
        val tail = "$crlf--$boundary--$crlf".encodeToByteArray()
        return ByteArray(head.size + audio.size + tail.size).also { out ->
            head.copyInto(out)
            audio.copyInto(out, head.size)
            tail.copyInto(out, head.size + audio.size)
        }
    }
}
