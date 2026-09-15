package brain.ios

import brain.ai.BuiltInAi
import brain.studio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IosAiReadinessTest {
    @Test fun speechChecksAuthorizationAndCurrentService() {
        assertEquals("permissionRequired", iosSpeechCapability(true, true, 0).reason)
        assertEquals("permissionDenied", iosSpeechCapability(true, true, 1).reason)
        assertEquals("permissionDenied", iosSpeechCapability(true, true, 2).reason)
        assertEquals("runtimeUnavailable", iosSpeechCapability(true, false, 3).reason)
        assertEquals("languageUnsupported", iosSpeechCapability(false, true, 3).reason)
        assertTrue(iosSpeechCapability(true, true, 3).executable)
    }
    @Test fun savedConnectionWithMissingKeyIsNotReady() = runTest {
        val f = Fixture()
        f.key = null
        assertEquals("apiKeyMissing", f.capability().reason)
        assertEquals(0, f.networkCalls)
    }
    @Test fun unavailableKeychainIsNotReady() = runTest {
        val f = Fixture()
        f.storeAvailable = false
        assertEquals("secureStoreUnavailable", f.capability().reason)
        assertEquals(0, f.keyReads)
    }
    @Test fun staleConsentDoesNotReadKeyOrSendAnything() = runTest {
        val f = Fixture()
        f.saved = f.saved.copy(privacyConsentVersion = 0)
        assertEquals("cloudConsentRequired", f.capability().reason)
        assertEquals(0, f.keyReads)
        assertEquals(0, f.networkCalls)
    }
    @Test fun keychainFailureIsNotMistakenForMissingKey() = runTest {
        val f = Fixture()
        f.failure = IllegalStateException("secret details must not enter state")
        val status = f.capability()
        assertEquals("capabilityCheckFailed", status.reason)
        assertFalse(status.toString().contains("secret details"))
    }
    @Test fun cancellationIsNotConvertedToUnavailable() = runTest {
        val f = Fixture()
        f.failure = CancellationException("cancel")
        assertFailsWith<CancellationException> { f.capability() }
    }
    @Test fun capabilityUsesCurrentKeyAndDoesNotWriteOrTestConnection() = runTest {
        val f = Fixture()
        assertTrue(f.capability().executable)
        f.key = null
        assertFalse(f.capability().executable)
        f.key = "replacement"
        assertTrue(f.capability().executable)
        assertEquals(0, f.networkCalls)
    }
    @Test fun brokenCloudMetadataDoesNotDisableNativeTextRoles() = runTest {
        val f = Fixture()
        val badMetadata = object : IosCloudMetadataStore {
            override fun read(): List<CloudAiConnection> = error("broken metadata")
            override fun write(value: List<CloudAiConnection>) = error("unexpected write")
        }
        val gateway = IosCloudAiGateway(f.secrets, badMetadata, f.transport)
        val selection = BuiltInAi.appleSelection().copy(speechToText = "cloud:openai:SPEECH_TO_TEXT")
        val router = IosRoutedIntelligence(IosOnDeviceIntelligence(), gateway) { Preferences(ai = selection) }
        val result = router.capabilities(selection, "ru").associateBy { it.role }
        assertEquals("capabilityCheckFailed", result.getValue(AiRole.SPEECH_TO_TEXT).reason)
        assertTrue(result.getValue(AiRole.TEXT).executable)
        assertTrue(result.getValue(AiRole.ROUTING).executable)
    }

    private class Fixture {
        var storeAvailable = true
        var key: String? = "test-key"
        var failure: Exception? = null
        var keyReads = 0
        var networkCalls = 0
        var saved = CloudAiConnection("openai", mapOf(AiRole.TEXT to "model"), enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION).let { it.copy(consentSnapshot = AiPrivacy.snapshot(it)) }
        val secrets = object : IosSecretStore {
            override val available get() = storeAvailable
            override fun get(providerId: String): String? { keyReads++; failure?.let { throw it }; return key }
            override fun put(providerId: String, value: String) = error("unexpected write")
            override fun remove(providerId: String): SecureSecretDeletion = error("unexpected delete")
        }
        val transport = object : IosCloudTransport {
            override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean { networkCalls++; error("unexpected network") }
            override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String { networkCalls++; error("unexpected network") }
            override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String { networkCalls++; error("unexpected network") }
        }
        private val metadata = object : IosCloudMetadataStore {
            override fun read() = listOf(saved)
            override fun write(value: List<CloudAiConnection>) = error("unexpected write")
        }
        private val gateway = IosCloudAiGateway(secrets, metadata, transport)
        suspend fun capability() = gateway.capability(AiRole.TEXT, "cloud:openai:TEXT", "openai")
    }
}
