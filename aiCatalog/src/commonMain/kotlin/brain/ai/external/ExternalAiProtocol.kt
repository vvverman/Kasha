package brain.ai.external

import brain.ai.CloudConnectionPolicy

import brain.studio.*
import kotlinx.serialization.json.*

/** Только инфраструктурный протокол. Секреты не сериализуются и не попадают в состояние Core. */
class ExternalRequest(val url: String, val headers: Map<String, String>, val body: String? = null)

object ExternalAiProtocol {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 64L * 1024 * 1024

    fun models(connection: CloudAiConnection, key: String): ExternalRequest {
        CloudConnectionPolicy.validate(connection)
        return ExternalRequest(join(base(connection), "models"), headers(connection.providerId, key))
    }

    fun generate(connection: CloudAiConnection, role: AiRole, key: String, prompt: String): ExternalRequest {
        CloudConnectionPolicy.validate(connection)
        require(role != AiRole.SPEECH_TO_TEXT) { "cloudRoleUnsupported" }
        val model = connection.modelFor(role) ?: error("cloudModelRequired")
        val suffix = when (connection.providerId) {
            "openai" -> "responses"
            "anthropic" -> "messages"
            "gemini" -> {
                val id = model.removePrefix("models/")
                require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "cloudModelRequired" }
                "models/$id:generateContent"
            }
            else -> "chat/completions"
        }
        val body = buildJsonObject {
            when (connection.providerId) {
                "gemini" -> putJsonArray("contents") {
                    add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } })
                }
                "openai" -> { put("model", model); put("input", prompt) }
                else -> {
                    put("model", model)
                    if (connection.providerId == "anthropic") put("max_tokens", 4096)
                    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
                }
            }
        }.toString()
        return ExternalRequest(join(base(connection), suffix), headers(connection.providerId, key), body)
    }

    fun audio(connection: CloudAiConnection, key: String): ExternalRequest {
        CloudConnectionPolicy.validate(connection)
        require(connection.providerId in setOf("openai", "custom")) { "cloudSttUnsupported" }
        require(connection.modelFor(AiRole.SPEECH_TO_TEXT) != null) { "cloudModelRequired" }
        return ExternalRequest(join(base(connection), "audio/transcriptions"), headers(connection.providerId, key))
    }

    fun transcription(body: String): String = nonempty(Json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull)

    fun text(providerId: String, body: String): String {
        val root = Json.parseToJsonElement(body).jsonObject
        return nonempty(when (providerId) {
            "openai" -> root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: root["output"]?.jsonArray.orEmpty().flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            "anthropic" -> root["content"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            "gemini" -> root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            else -> root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        })
    }

    fun audioType(file: String): Pair<String, String> = when (file.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio.wav" to "audio/wav"
        "mp3" -> "audio.mp3" to "audio/mpeg"
        "webm" -> "audio.webm" to "audio/webm"
        "ogg" -> "audio.ogg" to "audio/ogg"
        else -> "audio.m4a" to "audio/mp4"
    }

    private fun base(connection: CloudAiConnection): String = when (connection.providerId) {
        "openai" -> "https://api.openai.com/v1"
        "anthropic" -> "https://api.anthropic.com/v1"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "openai-compatible", "custom" -> CloudConnectionPolicy.endpoint(connection.endpoint.orEmpty())
        else -> error("unknownAiProvider")
    }
    private fun headers(provider: String, key: String): Map<String, String> {
        require(key.isNotBlank() && key.none { it.code < 32 || it.code == 127 }) { "apiKeyRequired" }
        return when (provider) {
            "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            "gemini" -> mapOf("x-goog-api-key" to key)
            else -> mapOf("Authorization" to "Bearer $key")
        }
    }
    private fun join(base: String, suffix: String): String =
        if (base.endsWith("/$suffix")) base else "${base.trimEnd('/')}/$suffix"
    private fun nonempty(value: String?): String = value?.trim()?.takeIf(String::isNotBlank) ?: error("cloudEmptyResponse")
}
