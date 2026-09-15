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

/** iOS предоставляет только Keychain, файл метаданных и сетевой транспорт. */
internal class IosCloudAiGateway(
    secrets: IosSecretStore = IosKeychainSecretStore(),
    metadata: IosCloudMetadataStore = IosFileCloudMetadataStore(),
    transport: IosCloudTransport = IosExternalAiClient(),
) : CloudAiGateway {
    private val delegate = brain.ai.ManagedCloudGateway(
        object : brain.ai.CloudSecretStore {
            override val available: Boolean get() = secrets.available
            override suspend fun get(providerId: String) = secrets.get(providerId)
            override suspend fun put(providerId: String, value: String) { secrets.put(providerId, value) }
            override suspend fun remove(providerId: String) = secrets.remove(providerId)
        },
        object : brain.ai.CloudMetadataStore {
            override suspend fun read() = metadata.read()
            override suspend fun write(value: List<CloudAiConnection>) { metadata.write(value) }
        },
        object : brain.ai.CloudTransport {
            override suspend fun test(connection: CloudAiConnection, apiKey: String) = transport.test(connection, apiKey)
            override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String) = transport.generate(connection, role, apiKey, prompt)
            override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String) = transport.transcribe(connection, apiKey, file, language)
        },
    )
    override val available: Boolean get() = delegate.available
    override suspend fun connections() = delegate.connections()
    override suspend fun save(connection: CloudAiConnection, apiKey: String?) = delegate.save(connection, apiKey)
    override suspend fun remove(providerId: String) = delegate.remove(providerId)
    override suspend fun disconnect(providerId: String) = delegate.disconnect(providerId)
    override suspend fun test(connection: CloudAiConnection, apiKey: String?) = delegate.test(connection, apiKey)
    suspend fun capability(role: AiRole, engineId: String, providerId: String) = delegate.capability(role, engineId, providerId)
    suspend fun generate(providerId: String, role: AiRole, prompt: String) = delegate.generate(providerId, role, prompt)
    suspend fun transcribe(providerId: String, file: String, language: String) = delegate.transcribe(providerId, file, language)
}
