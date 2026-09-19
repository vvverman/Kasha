package ru.vrmn.kasha.android

import android.content.Context
import android.util.AtomicFile
import brain.ai.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/** В файле только consent и метаданные; ключи — исключительно Android Keystore. */
internal fun androidCloudAi(context: Context): ManagedCloudGateway {
    val secrets by lazy { AndroidSecretStore(context) }
    return ManagedCloudGateway(
        object : CloudSecretStore {
            override val available: Boolean get() = runCatching { secrets }.isSuccess
            override suspend fun get(providerId: String) = secrets.read(providerId)
            override suspend fun put(providerId: String, value: String) = secrets.write(providerId, value)
            override suspend fun remove(providerId: String) = if (secrets.remove(providerId)) SecureSecretDeletion.DELETED else SecureSecretDeletion.NOT_FOUND
        },
        object : CloudMetadataStore {
            private val file = AtomicFile(File(context.filesDir, "Kasha/ai/connections.json"))
            private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            override suspend fun read(): List<CloudAiConnection> = withContext(Dispatchers.IO) {
                val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return@withContext emptyList() }
                json.decodeFromString(bytes.toString(Charsets.UTF_8))
            }
            override suspend fun write(value: List<CloudAiConnection>) = withContext(Dispatchers.IO) {
                val parent = file.baseFile.parentFile!!
                check(parent.isDirectory || parent.mkdirs()) { "saveFailed" }
                val stream = file.startWrite()
                try { stream.write(json.encodeToString(value).toByteArray()); file.finishWrite(stream) }
                catch (error: Throwable) { file.failWrite(stream); throw error }
            }
        },
        brain.android.external.AndroidExternalAiClient(),
    )
}
