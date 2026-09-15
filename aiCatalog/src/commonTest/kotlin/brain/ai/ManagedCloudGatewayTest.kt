package brain.ai

import brain.ai.external.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedCloudGatewayTest {
    private fun connection(provider: String = "custom", endpoint: String = "https://example.invalid/v1"): CloudAiConnection {
        val value = CloudAiConnection(providerId = provider, modelIds = AiRole.entries.associateWith { "model" }, endpoint = endpoint,
            enabled = true, privacyConsentVersion = AiPrivacy.CONSENT_VERSION)
        return value.copy(consentSnapshot = AiPrivacy.snapshot(value))
    }
    private class Secrets : CloudSecretStore {
        val values = mutableMapOf<String, String>()
        override var available = true
        var reads = 0
        override suspend fun get(providerId: String): String? { reads++; return values[providerId] }
        override suspend fun put(providerId: String, value: String) { values[providerId] = value }
        override suspend fun remove(providerId: String) = if (values.remove(providerId) == null) SecureSecretDeletion.NOT_FOUND else SecureSecretDeletion.DELETED
    }
    private class Metadata : CloudMetadataStore {
        var values = emptyList<CloudAiConnection>()
        var fail = false
        override suspend fun read() = values
        override suspend fun write(value: List<CloudAiConnection>) { check(!fail) { "diskFull" }; values = value }
    }
    private class Transport : CloudTransport {
        var calls = 0
        var lastRole: AiRole? = null
        var wait = false
        override suspend fun test(connection: CloudAiConnection, apiKey: String): Boolean { calls++; return true }
        override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
            calls++; lastRole = role
            if (wait) awaitCancellation()
            return "result"
        }
        override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String {
            calls++; lastRole = AiRole.SPEECH_TO_TEXT; return "speech"
        }
    }
    private val secrets = Secrets()
    private val metadata = Metadata()
    private val transport = Transport()
    private val gateway = ManagedCloudGateway(secrets, metadata, transport)

    @Test fun noConsentNeverReadsKeyOrUsesNetwork() = runTest {
        assertFails { gateway.save(connection().copy(consentSnapshot = null), "secret") }
        assertEquals(0, secrets.reads); assertEquals(0, transport.calls); assertTrue(metadata.values.isEmpty())
    }
    @Test fun allThreeRolesExecuteIndependently() = runTest {
        gateway.save(connection(), "secret")
        assertEquals("speech", gateway.transcribe("custom", "audio.wav", "ru"))
        assertEquals(AiRole.SPEECH_TO_TEXT, transport.lastRole)
        assertEquals("result", gateway.generate("custom", AiRole.TEXT, "text")); assertEquals(AiRole.TEXT, transport.lastRole)
        assertEquals("result", gateway.generate("custom", AiRole.ROUTING, "projects")); assertEquals(AiRole.ROUTING, transport.lastRole)
        assertEquals(3, transport.calls)
        assertFalse(Json.encodeToString(metadata.values).contains("secret"))
    }
    @Test fun changingOneSelectionDoesNotChangeOtherRoles() {
        val first = AiSelection()
        val second = first.with(AiRole.TEXT, AiCatalog.cloudEngineId("custom", AiRole.TEXT))
        assertEquals(first.speechToText, second.speechToText); assertEquals(first.routing, second.routing)
    }
    @Test fun changedConsentSnapshotBlocksNextRequest() = runTest {
        gateway.save(connection(), "secret")
        metadata.values = listOf(connection().copy(modelIds = mapOf(AiRole.TEXT to "changed")))
        assertFails { gateway.generate("custom", AiRole.TEXT, "private") }; assertEquals(0, transport.calls)
    }
    @Test fun remoteEndpointCannotStealReusedKey() = runTest {
        gateway.save(connection(), "old-secret")
        val changed = connection(endpoint = "https://another.invalid/v1")
        assertFails { gateway.save(changed, null) }; assertFails { gateway.test(changed, null) }
        assertEquals(connection(), metadata.values.single()); assertEquals(0, transport.calls)
        gateway.save(changed, "new-secret"); assertEquals("new-secret", secrets.values["custom"])
    }
    @Test fun metadataWriteFailureRestoresPreviousKey() = runTest {
        gateway.save(connection(), "old")
        metadata.fail = true
        assertFails { gateway.save(connection(), "new") }
        assertEquals("old", secrets.values["custom"])
    }
    @Test fun failedFirstSaveRemovesUnpublishedSecret() = runTest {
        metadata.fail = true
        assertFails { gateway.save(connection(), "unpublished") }
        assertTrue(secrets.values.isEmpty()); assertTrue(metadata.values.isEmpty())
    }
    @Test fun transientConnectionTestDoesNotPersistAnything() = runTest {
        assertTrue(gateway.test(connection(), "transient"))
        assertTrue(metadata.values.isEmpty()); assertTrue(secrets.values.isEmpty()); assertEquals(1, transport.calls)
    }
    @Test fun disconnectCancelsActiveOperationAndBlocksFurtherRequests() = runTest {
        gateway.save(connection(), "secret"); transport.wait = true
        val job = launch { gateway.generate("custom", AiRole.TEXT, "text") }
        runCurrent(); assertEquals(1, transport.calls)
        assertTrue(gateway.disconnect("custom").metadataRemoved)
        job.join(); assertTrue(job.isCancelled)
        assertFails { gateway.generate("custom", AiRole.ROUTING, "text") }
        assertEquals(1, transport.calls); assertTrue(secrets.values.isEmpty())
    }
    @Test fun noSecureStoreMeansNoRequests() = runTest {
        secrets.available = false
        assertFails { gateway.save(connection(), "secret") }
        assertFalse(gateway.test(connection(), "secret"))
        assertFails { gateway.generate("custom", AiRole.TEXT, "private") }
        assertEquals(0, transport.calls)
    }
    @Test fun localHttpExceptionRequiresExactHost() {
        for (url in listOf("http://localhost:8000/v1", "http://127.0.0.1:8000/v1", "https://example.invalid/v1")) {
            assertEquals(url, CloudConnectionPolicy.endpoint(url))
        }
        for (url in listOf("http://localhost.evil.invalid/v1", "http://127.0.0.1.evil.invalid", "http://localhost@evil.invalid",
            "http://127.0.0.1:99999", "https://example.invalid?secret=1", "https://example.invalid#part", "https://a\\@b/v1", "ftp://localhost/v1")) {
            assertFails { CloudConnectionPolicy.endpoint(url) }
        }
    }
    @Test fun headerInjectionAndStaleConsentAreRejectedByProtocolItself() {
        assertFails { ExternalAiProtocol.models(connection(), "secret\r\nInjected: value") }
        assertFails { ExternalAiProtocol.generate(connection().copy(enabled = false), AiRole.TEXT, "secret", "private") }
        assertFails { ExternalAiProtocol.audio(connection().copy(consentSnapshot = null), "secret") }
    }
    @Test fun protocolParsesAllSupportedProvidersAndPreservesAudioType() {
        assertEquals("hello", ExternalAiProtocol.text("openai", "{\"output\":[{\"content\":[{\"text\":\"hello\"}]}]}"))
        assertEquals("hello", ExternalAiProtocol.text("anthropic", "{\"content\":[{\"text\":\"hello\"}]}"))
        assertEquals("hello", ExternalAiProtocol.text("gemini", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}"))
        assertEquals("hello", ExternalAiProtocol.text("custom", "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"))
        assertEquals("hello", ExternalAiProtocol.transcription("{\"text\":\"hello\"}"))
        assertEquals("audio.wav" to "audio/wav", ExternalAiProtocol.audioType("private-name.wav"))
    }
}
