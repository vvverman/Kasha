package brain.android.external

import brain.ai.*
import brain.ai.external.*
import brain.studio.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Android-транспорт без редиректов, дискового HTTP-кеша и логирования запросов. */
internal class AndroidExternalAiClient(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : CloudTransport {
    override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean =
        send(ExternalAiProtocol.models(connection, apiKey), testOnly = true).first in 200..299

    override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String =
        ExternalAiProtocol.text(connection.providerId, send(ExternalAiProtocol.generate(connection, role, apiKey, prompt)).second)

    override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String {
        val request = ExternalAiProtocol.audio(connection, apiKey)
        val source = File(file)
        require(source.isFile && source.length() in 1..ExternalAiProtocol.MAX_AUDIO_BYTES) { "cloudAudioTooLarge" }
        val boundary = "Kasha-${UUID.randomUUID()}"
        val (name, type) = ExternalAiProtocol.audioType(file)
        fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        val prefix = field("model", connection.modelFor(AiRole.SPEECH_TO_TEXT)!!) +
            (if (language.isNotBlank() && language != "system") field("language", language) else "") +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n"
        val response = send(request, upload = source, prefix = prefix.toByteArray(),
            suffix = "\r\n--$boundary--\r\n".toByteArray(), contentType = "multipart/form-data; boundary=$boundary")
        return ExternalAiProtocol.transcription(response.second)
    }

    private suspend fun send(request: ExternalRequest, testOnly: Boolean = false, upload: File? = null,
        prefix: ByteArray = byteArrayOf(), suffix: ByteArray = byteArrayOf(),
        contentType: String = "application/json"): Pair<Int, String> = coroutineScope {
        ensureActive()
        val connection = open(URL(request.url))
        val operation = async(Dispatchers.IO) {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = if (testOnly) 30_000 else 300_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = request.body?.toByteArray(Charsets.UTF_8)
            if (body != null || upload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body?.size?.toLong() ?: (prefix.size + upload!!.length() + suffix.size))
                ensureActive()
                connection.outputStream.use { output ->
                    if (body != null) output.write(body) else {
                        output.write(prefix)
                        upload!!.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        output.write(suffix)
                    }
                }
            }
            ensureActive()
            val status = connection.responseCode
            if (testOnly) return@async status to ""
            check(status in 200..299) { "cloudHttp$status" }
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    check(result.size() + count <= ExternalAiProtocol.MAX_RESPONSE_BYTES) { "cloudResponseTooLarge" }
                    result.write(buffer, 0, count)
                }
            }
            status to result.toString("UTF-8")
        }
        try { operation.await() }
        finally {
            // Отмена ожидания закрывает сокет и освобождает заблокированный IO worker.
            connection.disconnect()
            operation.cancel()
        }
    }
}
