package brain.runtime.ai

import brain.ai.*
import brain.runtime.ai.external.ExternalAiClient
import brain.studio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Общие правила подключений; здесь только системные секреты и локальный файл метаданных. */
class JvmCloudAiGateway(
    root: Path,
    secrets: SecureSecretStore = platformSecretStore(),
    external: ExternalAiClient = ExternalAiClient(),
) : CloudAiGateway {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = root.resolve("ai").resolve("connections.json")
    private val delegate = ManagedCloudGateway(
        object : CloudSecretStore {
            override val available: Boolean get() = secrets.available
            override suspend fun get(providerId: String) = secrets.get(providerId)
            override suspend fun put(providerId: String, value: String) = secrets.put(providerId, value)
            override suspend fun remove(providerId: String): SecureSecretDeletion {
                val existed = !secrets.get(providerId).isNullOrBlank()
                secrets.remove(providerId)
                return if (!secrets.get(providerId).isNullOrBlank()) SecureSecretDeletion.FAILED
                    else if (existed) SecureSecretDeletion.DELETED else SecureSecretDeletion.NOT_FOUND
            }
        },
        object : CloudMetadataStore {
            override suspend fun read(): List<CloudAiConnection> = withContext(Dispatchers.IO) {
                if (!Files.exists(file)) emptyList() else json.decodeFromString(Files.readString(file))
            }
            override suspend fun write(value: List<CloudAiConnection>) = withContext(Dispatchers.IO) {
                Files.createDirectories(file.parent)
                val temp = Files.createTempFile(file.parent, ".connections-", ".tmp")
                try {
                    Files.writeString(temp, json.encodeToString(value))
                    try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
                } finally { Files.deleteIfExists(temp) }
                Unit
            }
        }, external,
    )
    override val available: Boolean get() = delegate.available
    override suspend fun connections() = delegate.connections()
    override suspend fun save(connection: CloudAiConnection, apiKey: String?) = delegate.save(connection, apiKey)
    override suspend fun remove(providerId: String) = delegate.remove(providerId)
    override suspend fun disconnect(providerId: String) = delegate.disconnect(providerId)
    override suspend fun test(connection: CloudAiConnection, apiKey: String?) = delegate.test(connection, apiKey)
    suspend fun capability(role: AiRole, engineId: String, providerId: String) = delegate.capability(role, engineId, providerId)
    suspend fun generate(providerId: String, role: AiRole, prompt: String) = delegate.generate(providerId, role, prompt)
    suspend fun transcribe(providerId: String, file: Path, language: String) = delegate.transcribe(providerId, file.toString(), language)
}

class AiStudioRepository(
    private val delegate: StudioRepository,
    override val aiPackages: AiPackageGateway,
    override val cloudAi: CloudAiGateway,
    runtimeReady: suspend (AiRole) -> Boolean = JvmAiRuntimeProbe(System.getenv())::available,
) : StudioRepository by delegate, AiPlatformServices {
    override val aiExecution: AiExecutionCapabilityGateway = JvmAiExecutionCapabilities(
        aiPackages, cloudAi, runtimeReady,
        language = { Languages.resolve(delegate.preferences().language, Locale.getDefault().toLanguageTag()) },
    )

    override suspend fun savePreferences(value: Preferences) {
        AiCatalog.validateSelection(value.ai)
        delegate.savePreferences(value)
    }
}
