package brain.runtime.ai

import brain.runtime.ai.external.ExternalAiClient
import brain.studio.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class JvmCloudAiGateway(
    private val root: Path,
    private val secrets: SecureSecretStore = platformSecretStore(),
    private val external: ExternalAiClient = ExternalAiClient(),
) : CloudAiGateway {
    override val available: Boolean get() = secrets.available
    private val file = root.resolve("ai").resolve("connections.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override suspend fun connections(): List<CloudAiConnection> = mutex.withLock { readUnsafe() }

    override suspend fun save(connection: CloudAiConnection, apiKey: String?) {
        require(available) { "Системное защищённое хранилище недоступно" }
        validate(connection)
        val existingSecret = if (apiKey.isNullOrBlank()) secrets.get(connection.providerId) else null
        require(!apiKey.isNullOrBlank() || !existingSecret.isNullOrBlank()) { "Нужен API-ключ" }
        if (!apiKey.isNullOrBlank()) secrets.put(connection.providerId, apiKey)
        mutex.withLock {
            val next = readUnsafe().filterNot { it.providerId == connection.providerId } + connection
            writeUnsafe(next.sortedBy { it.providerId })
        }
    }

    override suspend fun remove(providerId: String) {
        mutex.withLock { writeUnsafe(readUnsafe().filterNot { it.providerId == providerId }) }
        secrets.remove(providerId)
    }

    override suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean {
        if (!available) return false
        validate(connection)
        val key = apiKey?.takeIf(String::isNotBlank) ?: secrets.get(connection.providerId) ?: return false
        return external.test(connection, key)
    }

    suspend fun connection(providerId: String): CloudAiConnection = connections()
        .firstOrNull { it.providerId == providerId && it.enabled && it.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION }
        ?: error("Внешний AI $providerId не подключён")

    suspend fun key(providerId: String): String = secrets.get(providerId)?.takeIf(String::isNotBlank)
        ?: error("API-ключ $providerId отсутствует в защищённом хранилище")

    suspend fun generate(providerId: String, role: AiRole, prompt: String): String {
        val connection = connection(providerId)
        require(connection.modelFor(role) != null) { "Для $role не выбрана модель" }
        return external.generate(connection, role, key(providerId), prompt)
    }

    suspend fun transcribe(providerId: String, file: Path, language: String): String {
        val connection = connection(providerId)
        return external.transcribe(connection, key(providerId), file, language)
    }

    private fun validate(connection: CloudAiConnection) {
        val provider = AiCatalog.provider(connection.providerId) ?: error("Неизвестный AI provider")
        require(connection.enabled)
        require(connection.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION) { "Нужно подтвердить передачу данных" }
        require(connection.roles.isNotEmpty()) { "Выберите хотя бы одну модель" }
        require(connection.roles.all { it in provider.roles }) { "Provider не поддерживает выбранную роль" }
        if (provider.endpointRequired) {
            val endpoint = connection.endpoint.orEmpty()
            require(endpoint.startsWith("https://") || endpoint.startsWith("http://localhost") || endpoint.startsWith("http://127.0.0.1")) {
                "Custom endpoint должен использовать HTTPS; HTTP разрешён только для localhost"
            }
        }
    }

    private fun readUnsafe(): List<CloudAiConnection> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching { json.decodeFromString<List<CloudAiConnection>>(Files.readString(file)) }.getOrDefault(emptyList())
    }

    private fun writeUnsafe(value: List<CloudAiConnection>) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(".${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(value))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class AiStudioRepository(
    private val delegate: StudioRepository,
    override val aiPackages: AiPackageGateway,
    override val cloudAi: CloudAiGateway,
) : StudioRepository by delegate, AiPlatformServices {
    override suspend fun savePreferences(value: Preferences) {
        AiCatalog.validateSelection(value.ai)
        delegate.savePreferences(value)
    }
}
