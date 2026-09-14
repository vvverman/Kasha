@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios.external

import brain.ios.IosCloudTransport
import brain.studio.AiRole
import brain.studio.CloudAiConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.*
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

/** Единственное место iOS shell с реальными endpoint-ами внешнего inference. */
internal class IosExternalAiClient(
    private val client: HttpClient = HttpClient(Darwin),
) : IosCloudTransport {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean {
        val response = when (connection.providerId) {
            "openai" -> client.get("https://api.openai.com/v1/models") { bearer(apiKey) }
            "anthropic" -> client.get("https://api.anthropic.com/v1/models") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
            "gemini" -> client.get("https://generativelanguage.googleapis.com/v1beta/models") {
                header("x-goog-api-key", apiKey)
            }
            "openrouter" -> client.get("https://openrouter.ai/api/v1/models") { bearer(apiKey) }
            "openai-compatible", "custom" -> client.get(join(endpoint(connection), "models")) { bearer(apiKey) }
            else -> return false
        }
        return response.status.value in 200..299
    }

    override suspend fun generate(
        connection: CloudAiConnection,
        role: AiRole,
        apiKey: String,
        prompt: String,
    ): String {
        require(role != AiRole.SPEECH_TO_TEXT)
        val model = connection.modelFor(role) ?: error("cloudModelRequired")
        return when (connection.providerId) {
            "openai" -> openAiResponses(model, apiKey, prompt)
            "anthropic" -> anthropic(model, apiKey, prompt)
            "gemini" -> gemini(model, apiKey, prompt)
            "openrouter" -> chatCompletions("https://openrouter.ai/api/v1/chat/completions", model, apiKey, prompt)
            "openai-compatible", "custom" -> chatCompletions(join(endpoint(connection), "chat/completions"), model, apiKey, prompt)
            else -> error("unknownAiProvider")
        }
    }

    override suspend fun transcribe(
        connection: CloudAiConnection,
        apiKey: String,
        file: String,
        language: String,
    ): String {
        val model = connection.modelFor(AiRole.SPEECH_TO_TEXT) ?: error("cloudModelRequired")
        val url = when (connection.providerId) {
            "openai" -> "https://api.openai.com/v1/audio/transcriptions"
            "custom" -> join(endpoint(connection), "audio/transcriptions")
            else -> error("cloudSttUnsupported")
        }
        val response = client.post(url) {
            bearer(apiKey)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        if (language.isNotBlank() && language != "system") append("language", language)
                        append(
                            "file",
                            fileBytes(file),
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/mp4")
                                append(HttpHeaders.ContentDisposition, "filename=\"audio.m4a\"")
                            },
                        )
                    }
                )
            )
        }
        requireSuccess(response.status.value)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["text"]
            ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            ?: error("cloudEmptyTranscription")
    }

    private suspend fun openAiResponses(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
        }.toString()
        val response = client.post("https://api.openai.com/v1/responses") {
            bearer(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        requireSuccess(response.status.value)
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        return root["output"]?.jsonArray.orEmpty().asSequence()
            .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty().asSequence() }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n").trim().takeIf(String::isNotBlank)
            ?: error("cloudEmptyResponse")
    }

    private suspend fun anthropic(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
        }.toString()
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        requireSuccess(response.status.value)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("cloudEmptyResponse")
    }

    private suspend fun gemini(model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
        }.toString()
        val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/${encodePath(model)}:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        requireSuccess(response.status.value)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)
            ?: error("cloudEmptyResponse")
    }

    private suspend fun chatCompletions(url: String, model: String, apiKey: String, prompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
        }.toString()
        val response = client.post(url) {
            bearer(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        requireSuccess(response.status.value)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank)
            ?: error("cloudEmptyResponse")
    }

    private fun HttpRequestBuilder.bearer(apiKey: String) {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
    }

    private fun endpoint(connection: CloudAiConnection): String = connection.endpoint
        ?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
        ?: error("cloudEndpointRequired")

    private fun join(base: String, suffix: String): String {
        val normalized = base.trimEnd('/')
        return if (normalized.endsWith("/$suffix")) normalized else "$normalized/$suffix"
    }

    private fun requireSuccess(status: Int) {
        require(status in 200..299) { "cloudHttp$status" }
    }

    private fun encodePath(value: String): String = value
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace("?", "%3F")
        .replace("#", "%23")

    private fun fileBytes(path: String): ByteArray {
        val handle = fopen(path, "rb") ?: error("cloudAudioMissing")
        return try {
            check(fseek(handle, 0L, SEEK_END) == 0) { "cloudAudioReadFailed" }
            val length = ftell(handle)
            check(length >= 0L && length <= Int.MAX_VALUE.toLong()) { "cloudAudioTooLarge" }
            rewind(handle)

            ByteArray(length.toInt()).also { output ->
                if (output.isNotEmpty()) {
                    val read = output.usePinned { pinned ->
                        fread(pinned.addressOf(0), 1uL, output.size.toULong(), handle)
                    }
                    check(read == output.size.toULong()) { "cloudAudioReadFailed" }
                }
            }
        } finally {
            fclose(handle)
        }
    }
}
