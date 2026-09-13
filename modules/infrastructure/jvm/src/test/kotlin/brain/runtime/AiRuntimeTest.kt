package brain.runtime

import brain.runtime.ai.*
import brain.studio.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AiRuntimeTest {
    @Test
    fun modelManifestHasPinnedHttpsAndHashes() {
        val specs = JvmModelManifest.packages.values
        assertTrue(specs.isNotEmpty())
        assertEquals(specs.size, specs.map { it.engineId }.toSet().size)
        specs.forEach { spec ->
            assertTrue(spec.url.startsWith("https://"), spec.engineId)
            assertTrue(Regex("[0-9a-f]{64}").matches(spec.sha256), spec.engineId)
            assertNotNull(AiCatalog.engine(spec.engineId))
        }
    }

    @Test
    fun bundledModelsAreDetectedWithoutDownload() = runBlocking {
        val root = Files.createTempDirectory("kasha-ai-models-")
        try {
            val bundled = root.resolve("small.bin"); Files.write(bundled, byteArrayOf(1, 2, 3))
            val gateway = JvmAiPackageGateway(root, mapOf(AiCatalog.DEFAULT_STT to bundled))
            val state = gateway.states().first { it.engineId == AiCatalog.DEFAULT_STT }
            assertTrue(state.installed)
            assertEquals(bundled, gateway.modelPath(AiCatalog.DEFAULT_STT))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun cloudMetadataNeverStoresApiKey() = runBlocking {
        val root = Files.createTempDirectory("kasha-ai-cloud-")
        val secrets = MemorySecrets()
        try {
            val gateway = JvmCloudAiGateway(root, secrets)
            val connection = CloudAiConnection(
                providerId = "openai",
                modelIds = mapOf(AiRole.TEXT to "test-model"),
                enabled = true,
                privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
            )
            gateway.save(connection, "SUPER-SECRET")
            assertEquals("SUPER-SECRET", secrets.get("openai"))
            val metadata = Files.readString(root.resolve("ai/connections.json"))
            assertFalse(metadata.contains("SUPER-SECRET"))
            assertFalse(metadata.contains("apiKey", ignoreCase = true))
            assertEquals("test-model", gateway.connections().single().modelFor(AiRole.TEXT))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun cloudConnectionRequiresCurrentConsent() = runBlocking {
        val root = Files.createTempDirectory("kasha-ai-consent-")
        try {
            val gateway = JvmCloudAiGateway(root, MemorySecrets())
            val bad = CloudAiConnection(
                providerId = "openai",
                modelIds = mapOf(AiRole.TEXT to "test-model"),
                enabled = true,
                privacyConsentVersion = 0,
            )
            assertFailsWith<IllegalArgumentException> { gateway.save(bad, "secret") }
        } finally { root.toFile().deleteRecursively() }
    }

    private class MemorySecrets : SecureSecretStore {
        private val values = mutableMapOf<String, String>()
        override val available = true
        override suspend fun get(id: String): String? = values[id]
        override suspend fun put(id: String, value: String) { values[id] = value }
        override suspend fun remove(id: String) { values.remove(id) }
    }
}
