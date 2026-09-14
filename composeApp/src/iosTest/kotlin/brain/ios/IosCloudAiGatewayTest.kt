package brain.ios

import brain.studio.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class IosCloudAiGatewayTest {
    @Test
    fun saveKeepsApiKeyOutsideSerializedMetadata() = runTest {
        val secrets = FakeSecretStore()
        val metadata = FakeMetadataStore()
        val gateway = IosCloudAiGateway(secrets, metadata, FakeTransport())
        val connection = connection()

        gateway.save(connection, "super-secret-key")

        assertEquals("super-secret-key", secrets.values["openai"])
        assertEquals(listOf(connection), metadata.values)
        val serialized = Json.encodeToString(metadata.values)
        assertFalse(serialized.contains("super-secret-key"))
        assertFalse(serialized.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun blankSaveReusesExistingKeyWithoutReplacingIt() = runTest {
        val secrets = FakeSecretStore(mutableMapOf("openai" to "existing-key"))
        val metadata = FakeMetadataStore()
        val gateway = IosCloudAiGateway(secrets, metadata, FakeTransport())

        gateway.save(connection(), null)

        assertEquals("existing-key", secrets.values["openai"])
        assertEquals(1, metadata.values.size)
    }

    @Test
    fun transientTestKeyIsNeverPersisted() = runTest {
        val secrets = FakeSecretStore()
        val transport = FakeTransport()
        val gateway = IosCloudAiGateway(secrets, FakeMetadataStore(), transport)

        assertTrue(gateway.test(connection(), "one-shot-key"))
        assertEquals("one-shot-key", transport.lastKey)
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun disconnectRemovesMetadataAndSecret() = runTest {
        val secrets = FakeSecretStore(mutableMapOf("openai" to "stored-key"))
        val metadata = FakeMetadataStore(mutableListOf(connection()))
        val gateway = IosCloudAiGateway(secrets, metadata, FakeTransport())

        val result = gateway.disconnect("openai")

        assertTrue(result.metadataRemoved)
        assertEquals(SecureSecretDeletion.DELETED, result.secretDeletion)
        assertTrue(metadata.values.isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun insecureRemoteEndpointIsRejectedBeforeSecretStorage() = runTest {
        val secrets = FakeSecretStore()
        val gateway = IosCloudAiGateway(secrets, FakeMetadataStore(), FakeTransport())
        val base = CloudAiConnection(
            providerId = "custom",
            modelIds = mapOf(AiRole.TEXT to "model"),
            endpoint = "http://example.com/api",
            enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
        )
        val invalid = base.copy(consentSnapshot = AiPrivacy.snapshot(base))

        assertFails { gateway.save(invalid, "secret") }
        assertTrue(secrets.values.isEmpty())
    }

    private fun connection(): CloudAiConnection {
        val base = CloudAiConnection(
            providerId = "openai",
            modelIds = mapOf(AiRole.TEXT to "gpt-test"),
            enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
        )
        return base.copy(consentSnapshot = AiPrivacy.snapshot(base))
    }

    private class FakeSecretStore(
        val values: MutableMap<String, String> = mutableMapOf(),
    ) : IosSecretStore {
        override val available: Boolean = true
        override fun get(providerId: String): String? = values[providerId]
        override fun put(providerId: String, value: String) { values[providerId] = value }
        override fun remove(providerId: String): SecureSecretDeletion =
            if (values.remove(providerId) != null) SecureSecretDeletion.DELETED else SecureSecretDeletion.NOT_FOUND
    }

    private class FakeMetadataStore(
        val values: MutableList<CloudAiConnection> = mutableListOf(),
    ) : IosCloudMetadataStore {
        override fun read(): List<CloudAiConnection> = values.toList()
        override fun write(value: List<CloudAiConnection>) {
            values.clear()
            values.addAll(value)
        }
    }

    private class FakeTransport : IosCloudTransport {
        var lastKey: String? = null
        override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean {
            lastKey = apiKey
            return true
        }
        override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
            lastKey = apiKey
            return "ok"
        }
        override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String {
            lastKey = apiKey
            return "ok"
        }
    }
}
