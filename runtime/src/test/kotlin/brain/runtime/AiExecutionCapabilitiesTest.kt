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
        val ready = gateway().roles(AiSelection())
        assertEquals(AiRole.entries.toSet(), ready.map { it.role }.toSet())
        assertTrue(ready.all { it.executable })
        assertTrue(gateway(installed = false).roles(AiSelection()).all { !it.executable && it.reason == "modelNotInstalled" })
    }
    @Test fun missingPackageBlocksOnlyItsOwnRole() = runBlocking {
        for (missingRole in AiRole.entries) {
            val capabilities = gateway(missingRoles = setOf(missingRole)).roles(AiSelection())
            val blocked = capabilities.single { it.role == missingRole }
            assertFalse(blocked.executable, missingRole.name)
            assertEquals("modelNotInstalled", blocked.reason, missingRole.name)
            assertTrue(capabilities.filter { it.role != missingRole }.all { it.executable }, missingRole.name)
        }
    }
    @Test fun legacySelectionRequiresSeparateCleanupAndRoutingPackages() = runBlocking {
        for (legacyTextId in listOf("local.qwen3.4b", "local.qwen.8b")) {
            val selection = AiSelection("local.whisper.small", legacyTextId, legacyTextId)
            assertTrue(gateway().roles(selection).all { it.executable }, legacyTextId)
            val capabilities = gateway(missingRoles = setOf(AiRole.ROUTING)).roles(selection)
            assertTrue(capabilities.single { it.role == AiRole.TEXT }.executable, legacyTextId)
            val routing = capabilities.single { it.role == AiRole.ROUTING }
            assertFalse(routing.executable, legacyTextId)
            assertEquals("modelNotInstalled", routing.reason, legacyTextId)
        }
    }
    @Test fun routingRuntimeFailureDoesNotBlockSttOrCleanup() = runBlocking {
        val capabilities = gateway(runtime = { it != AiRole.ROUTING }).roles(AiSelection())
        val routing = capabilities.single { it.role == AiRole.ROUTING }
        assertFalse(routing.executable)
        assertEquals("runtimeUnavailable", routing.reason)
        assertTrue(capabilities.filter { it.role != AiRole.ROUTING }.all { it.executable })
    }
    @Test fun wrongRoleAndNativeEngineAreNotClaimedAsRunnable() = runBlocking {
        val selection = AiSelection("local.default.text", "native.kasha.rules", "cloud:openai:TEXT")
        assertTrue(gateway().roles(selection).all { !it.executable && it.reason == "platformUnavailable" })
    }
    @Test fun runtimeProbeStartsOnlyToolsWithoutModelOrUserData() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val probe = JvmAiRuntimeProbe(mapOf(
            "KASHA_WHISPER_CLI" to "stt",
            "KASHA_LLAMA_CLI" to "llm",
            "KASHA_EMBEDDING_CLI" to "embedding",
            "KASHA_FFMPEG" to "codec",
        ), CommandRunner { command, _ -> calls += command; "version" })
        assertTrue(probe.available(AiRole.SPEECH_TO_TEXT))
        assertTrue(probe.available(AiRole.TEXT))
        assertTrue(probe.available(AiRole.ROUTING))
        assertEquals(listOf(
            listOf("stt", "--version"),
            listOf("codec", "-version"),
            listOf("llm", "--version"),
            listOf("embedding", "--version"),
        ), calls)
    }
    @Test fun missingEmbeddingExecutableDoesNotUseCleanupExecutable() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_LLAMA_CLI" to "llm"),
            CommandRunner { command, _ -> calls += command; "version" })
        assertFalse(probe.available(AiRole.ROUTING))
        assertTrue(calls.isEmpty())
    }
    @Test fun runtimeFailureDoesNotClaimReady() = runBlocking {
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_LLAMA_CLI" to "broken"), CommandRunner { _, _ -> error("missing dependency") })
        assertFalse(probe.available(AiRole.TEXT))
    }
    @Test fun cancelledProbeRemainsCancelled(): Unit = runBlocking {
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_LLAMA_CLI" to "cancelled"), CommandRunner { _, _ -> throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { probe.available(AiRole.TEXT) }
        Unit
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
    private fun gateway(
        installed: Boolean = true,
        missingRoles: Set<AiRole> = emptySet(),
        runtime: suspend (AiRole) -> Boolean = { true },
    ): JvmAiExecutionCapabilities {
        val packages = object : AiPackageGateway {
            override val available = true
            override suspend fun states(): List<AiPackageState> {
                val selection = AiSelection()
                // Each role now owns a separate package, including routing embeddings.
                return AiRole.entries.map { role ->
                    AiPackageState(selection.engineId(role), installed && role !in missingRoles)
                }
            }
            override suspend fun install(engineId: String) = error("unexpected install")
            override suspend fun remove(engineId: String) = error("unexpected remove")
        }
        return JvmAiExecutionCapabilities(packages, NoopCloudAiGateway, runtime) { "ru" }
    }
}
