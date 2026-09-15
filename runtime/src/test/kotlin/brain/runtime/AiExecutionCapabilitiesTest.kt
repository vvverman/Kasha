package brain.runtime

import brain.runtime.ai.*
import brain.studio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AiExecutionCapabilitiesTest {
    @Test fun installedPackageCannotHideMissingRuntime() = runBlocking {
        val gateway = gateway(runtime = { false })
        assertTrue(gateway.roles(AiSelection()).all { !it.executable && it.reason == "runtimeUnavailable" })
    }
    @Test fun validRuntimeAndPackagesAreRequiredTogether() = runBlocking {
        assertTrue(gateway().roles(AiSelection()).all { it.executable })
        assertTrue(gateway(installed = false).roles(AiSelection()).all { it.reason == "modelNotInstalled" })
    }
    @Test fun wrongRoleAndNativeEngineAreNotClaimedAsRunnable() = runBlocking {
        val selection = AiSelection("local.default.text", "native.kasha.rules", "cloud:openai:TEXT")
        assertTrue(gateway().roles(selection).all { !it.executable && it.reason == "platformUnavailable" })
    }
    @Test fun runtimeProbeStartsOnlyToolsWithoutModelOrUserData() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_WHISPER_CLI" to "stt", "KASHA_LLAMA_CLI" to "llm", "KASHA_FFMPEG" to "codec"),
            CommandRunner { command, _ -> calls += command; "help" })
        assertTrue(probe.available(AiRole.SPEECH_TO_TEXT))
        assertTrue(probe.available(AiRole.TEXT))
        assertEquals(listOf(listOf("stt", "--help"), listOf("codec", "-version"), listOf("llm", "--help")), calls)
    }
    @Test fun runtimeFailureDoesNotClaimReady() = runBlocking {
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_LLAMA_CLI" to "broken"), CommandRunner { _, _ -> error("missing dependency") })
        assertFalse(probe.available(AiRole.TEXT))
    }
    @Test fun cancelledProbeRemainsCancelled() = runBlocking {
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_LLAMA_CLI" to "cancelled"), CommandRunner { _, _ -> throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { probe.available(AiRole.TEXT) }
    }
    @Test fun cloudReadsKeyPresenceWithoutReturningIt() = runBlocking {
        val root = Files.createTempDirectory("kasha-ready-")
        var key: String? = "test-secret"
        val secrets = object : SecureSecretStore {
            override val available = true
            override suspend fun get(id: String) = key
            override suspend fun put(id: String, value: String) { key = value }
            override suspend fun remove(id: String) { key = null }
        }
        try {
            val cloud = JvmCloudAiGateway(root, secrets)
            val value = CloudAiConnection("openai", mapOf(AiRole.TEXT to "test-model"), enabled = true,
                privacyConsentVersion = AiPrivacy.CONSENT_VERSION)
            cloud.save(value.copy(consentSnapshot = AiPrivacy.snapshot(value)), key)
            assertTrue(cloud.capability(AiRole.TEXT, "cloud:openai:TEXT", "openai").executable)
            key = null
            val unavailable = cloud.capability(AiRole.TEXT, "cloud:openai:TEXT", "openai")
            assertEquals("credentialMissing", unavailable.reason)
            assertFalse(unavailable.toString().contains("test-secret"))
            Files.writeString(root.resolve("ai/connections.json"), "broken")
            assertEquals("capabilityCheckFailed", cloud.capability(AiRole.TEXT, "cloud:openai:TEXT", "openai").reason)
        } finally { root.toFile().deleteRecursively() }
    }
    private fun gateway(installed: Boolean = true, runtime: suspend (AiRole) -> Boolean = { true }): JvmAiExecutionCapabilities {
        val packages = object : AiPackageGateway {
            override val available = true
            override suspend fun states() = listOf(AiPackageState(AiSelection.DEFAULT_STT, installed), AiPackageState(AiSelection.DEFAULT_TEXT, installed))
            override suspend fun install(engineId: String) = error("unexpected install")
            override suspend fun remove(engineId: String) = error("unexpected remove")
        }
        return JvmAiExecutionCapabilities(packages, NoopCloudAiGateway, runtime) { "ru" }
    }
}
