package brain.runtime.ai

interface SecureSecretStore {
    val available: Boolean
    suspend fun get(id: String): String?
    suspend fun put(id: String, value: String)
    suspend fun remove(id: String)
}

object UnsupportedSecretStore : SecureSecretStore {
    override val available = false
    override suspend fun get(id: String): String? = null
    override suspend fun put(id: String, value: String) = error("Защищённое хранилище недоступно")
    override suspend fun remove(id: String) = Unit
}
