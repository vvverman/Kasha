package brain.ios

import brain.ios.external.IosExternalAiClient
import brain.studio.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface IosCloudMetadataStore {
    fun read(): List<CloudAiConnection>
    fun write(value: List<CloudAiConnection>)
}

internal class IosFileCloudMetadataStore(private val path: String = IosPaths.aiConnectionsFile) : IosCloudMetadataStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun read(): List<CloudAiConnection> = IosPaths.read(path)
        ?.let { json.decodeFromString<List<CloudAiConnection>>(it) }.orEmpty()
    override fun write(value: List<CloudAiConnection>) { IosPaths.write(path, json.encodeToString(value)) }
}

internal interface IosCloudTransport {
    suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean
    suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String
    suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String
}

/** Метаданные локальны, ключ доступен только системному адаптеру. */
internal class IosCloudAiGateway(
    private val secrets: IosSecretStore = IosKeychainSecretStore(),
    private val metadata: IosCloudMetadataStore = IosFileCloudMetadataStore(),
    private val transport: IosCloudTransport = IosExternalAiClient(),
) : CloudAiGateway {
    override val available: Boolean get() = secrets.available
    override suspend fun connections(): List<CloudAiConnection> = metadata.read()

    suspend fun capability(role: AiRole, engineId: String, providerId: String): AiRoleCapability =
        AiReadiness.cloud(role, engineId, providerId, this) { !secrets.get(providerId).isNullOrBlank() }

    override suspend fun save(connection: CloudAiConnection, apiKey: String?) {
        require(available) { "secureStoreUnavailable" }
        validate(connection)
        val providerId = connection.providerId
        val previousSecret = secrets.get(providerId)
        val nextSecret = apiKey?.takeIf(String::isNotBlank) ?: previousSecret
        require(!nextSecret.isNullOrBlank()) { "apiKeyRequired" }
        if (!apiKey.isNullOrBlank()) secrets.put(providerId, apiKey)
        try {
            val next = metadata.read().filterNot { it.providerId == providerId }.plus(connection).sortedBy { it.providerId }
            metadata.write(next)
        } catch (error: Throwable) {
            if (!apiKey.isNullOrBlank()) {
                runCatching {
                    if (previousSecret.isNullOrBlank()) secrets.remove(providerId)
                    else secrets.put(providerId, previousSecret)
                }
            }
            throw error
        }
    }
    override suspend fun remove(providerId: String) { disconnect(providerId) }
    override suspend fun disconnect(providerId: String): CloudAiDisconnectResult {
        val before = metadata.read()
        val next = before.filterNot { it.providerId == providerId }
        val metadataRemoved = next != before
        if (metadataRemoved) metadata.write(next)
        val deletion = if (!available) SecureSecretDeletion.UNAVAILABLE else
            runCatching { secrets.remove(providerId) }.getOrElse { SecureSecretDeletion.FAILED }
        return CloudAiDisconnectResult(metadataRemoved, deletion)
    }
    override suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean {
        if (!available) return false
        validate(connection)
        val key = apiKey?.takeIf(String::isNotBlank) ?: secrets.get(connection.providerId) ?: return false
        return runCatching { transport.test(connection, key) }.getOrDefault(false)
    }
    suspend fun generate(providerId: String, role: AiRole, prompt: String): String {
        val connection = executableConnection(providerId, role)
        return transport.generate(connection, role, requireSecret(providerId), prompt)
    }
    suspend fun transcribe(providerId: String, file: String, language: String): String {
        val connection = executableConnection(providerId, AiRole.SPEECH_TO_TEXT)
        return transport.transcribe(connection, requireSecret(providerId), file, language)
    }
    private fun executableConnection(providerId: String, role: AiRole): CloudAiConnection = metadata.read()
        .firstOrNull { it.providerId == providerId && it.enabled && AiPrivacy.hasCurrentConsent(it) && it.modelFor(role) != null }
        ?: error("cloudConnectionUnavailable")
    private fun requireSecret(providerId: String): String = secrets.get(providerId)?.takeIf(String::isNotBlank) ?: error("apiKeyMissing")
    private fun validate(connection: CloudAiConnection) {
        val provider = AiCatalog.provider(connection.providerId) ?: error("unknownAiProvider")
        require(connection.enabled) { "cloudConnectionDisabled" }
        require(AiPrivacy.hasCurrentConsent(connection)) { "cloudConsentRequired" }
        require(connection.roles.isNotEmpty()) { "cloudModelRequired" }
        require(connection.roles.all { it in provider.roles }) { "cloudRoleUnsupported" }
        if (provider.endpointRequired) {
            val endpoint = connection.endpoint.orEmpty().trim()
            require(endpoint.startsWith("https://") || endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost")) {
                "cloudEndpointMustBeHttps"
            }
        }
    }
}
