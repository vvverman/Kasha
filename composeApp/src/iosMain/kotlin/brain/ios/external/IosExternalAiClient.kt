@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios.external

import brain.ai.external.*
import brain.ios.IosCloudTransport
import brain.studio.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.*

/** iOS-транспорт использует общий протокол и не перенаправляет ключи на другой endpoint. */
internal class IosExternalAiClient(
    private val client: HttpClient = HttpClient(Darwin) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 300_000; connectTimeoutMillis = 20_000; socketTimeoutMillis = 300_000 }
    },
) : IosCloudTransport {
    override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean {
        val request = ExternalAiProtocol.models(connection, apiKey)
        return client.prepareGet(request.url) { request.headers.forEach { (key, value) -> header(key, value) } }
            .execute { it.status.value in 200..299 }
    }
    override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
        val request = ExternalAiProtocol.generate(connection, role, apiKey, prompt)
        val body = client.preparePost(request.url) {
            request.headers.forEach { (key, value) -> header(key, value) }
            contentType(ContentType.Application.Json)
            setBody(request.body!!)
        }.execute { read(it) }
        return ExternalAiProtocol.text(connection.providerId, body)
    }
    override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String {
        val request = ExternalAiProtocol.audio(connection, apiKey)
        val (name, type) = ExternalAiProtocol.audioType(file)
        val bytes = fileBytes(file)
        val body = client.preparePost(request.url) {
            request.headers.forEach { (key, value) -> header(key, value) }
            setBody(MultiPartFormDataContent(formData {
                append("model", connection.modelFor(AiRole.SPEECH_TO_TEXT)!!)
                if (language.isNotBlank() && language != "system") append("language", language)
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, type)
                    append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                })
            }))
        }.execute { read(it) }
        return ExternalAiProtocol.transcription(body)
    }
    private suspend fun read(response: HttpResponse): String {
        check(response.status.value in 200..299) { "cloudHttp${response.status.value}" }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(ExternalAiProtocol.MAX_RESPONSE_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val count = channel.readAvailable(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        check(length <= ExternalAiProtocol.MAX_RESPONSE_BYTES) { "cloudResponseTooLarge" }
        return buffer.decodeToString(0, length)
    }

    private fun fileBytes(path: String): ByteArray {
        val handle = fopen(path, "rb") ?: error("cloudAudioMissing")
        return try {
            check(fseek(handle, 0L, SEEK_END) == 0) { "cloudAudioReadFailed" }
            val length = ftell(handle)
            check(length in 1..ExternalAiProtocol.MAX_AUDIO_BYTES) { "cloudAudioTooLarge" }
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
