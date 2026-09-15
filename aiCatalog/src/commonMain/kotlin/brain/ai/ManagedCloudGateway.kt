package brain.ai

import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CloudMetadataStore {
    suspend fun read(): List<CloudAiConnection>
    suspend fun write(value: List<CloudAiConnection>)
}
interface CloudSecretStore {
    val available: Boolean
    suspend fun get(providerId: String): String?
    suspend fun put(providerId: String, value: String)
    suspend fun remove(providerId: String): SecureSecretDeletion
}
interface CloudTransport {
    suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean
    suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String
    suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String
}

/** Общая инфраструктура подключений; конкретное хранилище и транспорт даёт платформа. */
class ManagedCloudGateway(
    private val secrets: CloudSecretStore,
    private val metadata: CloudMetadataStore,
    private val transport: CloudTransport,
) : CloudAiGateway {
    private val gate = Mutex()
    private val active = mutableMapOf<String, MutableSet<Job>>()
    override val available: Boolean get() = secrets.available
    override suspend fun connections(): List<CloudAiConnection> = gate.withLock { metadata.read() }

    suspend fun capability(role: AiRole, engineId: String, providerId: String): AiRoleCapability =
        AiReadiness.cloud(role, engineId, providerId, this) { !secrets.get(providerId).isNullOrBlank() }

    override suspend fun save(connection: CloudAiConnection, apiKey: String?) = gate.withLock {
        require(available) { "secureStoreUnavailable" }
        CloudConnectionPolicy.validate(connection)
        val before = metadata.read()
        CloudConnectionPolicy.requireReplacementKey(before.firstOrNull { it.providerId == connection.providerId }, connection, apiKey)
        val id = connection.providerId
        val previous = secrets.get(id)
        require(!apiKey.isNullOrBlank() || !previous.isNullOrBlank()) { "apiKeyRequired" }
        // Секрет и метаданные публикуются без точки отмены между записью ключа и откатом.
        withContext(NonCancellable) {
            if (!apiKey.isNullOrBlank()) secrets.put(id, apiKey)
            try { metadata.write((before.filterNot { it.providerId == id } + connection).sortedBy { it.providerId }) }
            catch (error: Throwable) {
                if (!apiKey.isNullOrBlank()) runCatching {
                    if (previous.isNullOrBlank()) secrets.remove(id) else secrets.put(id, previous)
                }
                throw error
            }
            active.remove(id)?.forEach { it.cancel() }
        }
        Unit
    }

    override suspend fun remove(providerId: String) { disconnect(providerId) }
    override suspend fun disconnect(providerId: String): CloudAiDisconnectResult = gate.withLock {
        val before = metadata.read()
        val next = before.filterNot { it.providerId == providerId }
        val removed = next != before
        withContext(NonCancellable) {
            if (removed) metadata.write(next)
            active.remove(providerId)?.forEach { it.cancel() }
            val deletion = if (!available) SecureSecretDeletion.UNAVAILABLE else
                runCatching { secrets.remove(providerId) }.getOrElse { SecureSecretDeletion.FAILED }
            CloudAiDisconnectResult(removed, deletion)
        }
    }

    override suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean {
        if (!available) return false
        CloudConnectionPolicy.validate(connection)
        val key = gate.withLock {
            CloudConnectionPolicy.requireReplacementKey(metadata.read().firstOrNull { it.providerId == connection.providerId }, connection, apiKey)
            apiKey?.takeIf(String::isNotBlank) ?: secrets.get(connection.providerId)
        } ?: return false
        return transport.test(connection, key)
    }

    suspend fun generate(providerId: String, role: AiRole, prompt: String): String =
        execute(providerId, role) { connection, key -> transport.generate(connection, role, key, prompt) }

    suspend fun transcribe(providerId: String, file: String, language: String): String =
        execute(providerId, AiRole.SPEECH_TO_TEXT) { connection, key -> transport.transcribe(connection, key, file, language) }

    private suspend fun <T> execute(id: String, role: AiRole, run: suspend (CloudAiConnection, String) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val input = gate.withLock {
            require(available) { "secureStoreUnavailable" }
            val connection = metadata.read().firstOrNull { it.providerId == id } ?: error("cloudConnectionUnavailable")
            CloudConnectionPolicy.validate(connection)
            require(connection.modelFor(role) != null) { "cloudModelRequired" }
            val key = secrets.get(id)?.takeIf(String::isNotBlank) ?: error("apiKeyMissing")
            active.getOrPut(id) { mutableSetOf() }.add(job)
            connection to key
        }
        try { ensureActive(); run(input.first, input.second) }
        finally { withContext(NonCancellable) { gate.withLock {
            active[id]?.let { it.remove(job); if (it.isEmpty()) active.remove(id) }
        } } }
    }
}
