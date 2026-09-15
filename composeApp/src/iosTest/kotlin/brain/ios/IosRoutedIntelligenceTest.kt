package brain.ios

import brain.ai.BuiltInAi
import brain.ai.BuiltInText
import brain.ai.KashaAiCatalog
import brain.model.Project
import brain.studio.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Проверяется настоящий iOS router; сеть заменена существующим транспортным портом. */
class IosRoutedIntelligenceTest {
    @Test
    fun localSpeechModelsCannotRunAsAppleSpeech() = runTest {
        for (engine in localIds(AiRole.SPEECH_TO_TEXT) + "local.unknown") {
            val f = Fixture(BuiltInAi.appleSelection().with(AiRole.SPEECH_TO_TEXT, engine))
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.router.transcribe("unused-audio", "ru", "")
            }.message)
            assertTrue(f.transport.calls.isEmpty())
            assertEquals(engine, f.preferences.ai.speechToText)
        }
    }

    @Test
    fun localTextModelsCannotRunAsRulesForTitle() = runTest {
        for (engine in localIds(AiRole.TEXT)) {
            val f = Fixture(BuiltInAi.appleSelection().with(AiRole.TEXT, engine))
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.router.title(SOURCE, "ru")
            }.message)
            assertTrue(f.transport.calls.isEmpty())
        }
    }

    @Test
    fun localTextModelsCannotRunAsRulesForTidy() = runTest {
        for (engine in localIds(AiRole.TEXT)) {
            val f = Fixture(BuiltInAi.appleSelection().with(AiRole.TEXT, engine))
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.router.tidy(SOURCE, "ru")
            }.message)
            assertTrue(f.transport.calls.isEmpty())
        }
    }

    @Test
    fun localRoutingModelsCannotRunAsWordMatching() = runTest {
        for (engine in localIds(AiRole.ROUTING)) {
            val f = Fixture(BuiltInAi.appleSelection().with(AiRole.ROUTING, engine))
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.router.rank(SOURCE, PROJECTS, "ru")
            }.message)
            assertTrue(f.transport.calls.isEmpty())
        }
    }

    @Test
    fun anotherNativeEngineIsNotSubstituted() = runTest {
        for (engine in listOf(BuiltInAi.ANDROID_SPEECH, BuiltInAi.LOCAL_RULES)) {
            val f = Fixture(BuiltInAi.appleSelection().with(AiRole.SPEECH_TO_TEXT, engine))
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.router.transcribe("unused-audio", "ru", "")
            }.message)
            assertTrue(f.transport.calls.isEmpty())
        }
    }

    @Test
    fun explicitRulesRunTheSharedBoundedImplementation() = runTest {
        val selection = BuiltInAi.appleSelection()
        val f = Fixture(selection)
        assertEquals(BuiltInText.title(SOURCE, "ru"), f.router.title(SOURCE, "ru"))
        assertEquals(BuiltInText.tidy(SOURCE, "ru"), f.router.tidy(SOURCE, "ru"))
        assertEquals(BuiltInText.rank(SOURCE, PROJECTS, "ru"), f.router.rank(SOURCE, PROJECTS, "ru"))
        assertTrue(f.transport.calls.isEmpty())
        assertEquals(selection, f.preferences.ai)
    }

    @Test
    fun nativeDescriptorsDoNotClaimInstalledLanguageModels() {
        val speech = assertNotNull(KashaAiCatalog.engine(BuiltInAi.APPLE_SPEECH))
        val rules = assertNotNull(KashaAiCatalog.engine(BuiltInAi.LOCAL_RULES))
        assertEquals("Apple Speech", speech.name)
        assertEquals("Локальные правила", rules.name)
        assertTrue(rules.description.contains("без языковой модели"))
        for (engine in listOf(speech, rules)) {
            assertEquals(AiLocality.NATIVE, engine.locality)
            assertFalse(engine.installable)
            assertFalse(engine.defaultInstalled)
        }
    }

    @Test
    fun cloudSelectionUsesItsProviderAndModelForEachRole() = runTest {
        val selection = cloudSelection()
        val f = Fixture(selection)
        assertEquals(SOURCE, f.router.transcribe("unused-audio", "ru", ""))
        assertEquals(SOURCE, f.router.title(SOURCE, "ru"))
        assertEquals(SOURCE, f.router.tidy(SOURCE, "ru"))
        assertEquals(mapOf("p" to 3), f.router.rank(SOURCE, PROJECTS, "ru"))
        assertEquals(listOf(
            "openai:SPEECH_TO_TEXT:openai-SPEECH_TO_TEXT",
            "anthropic:TEXT:anthropic-TEXT",
            "anthropic:TEXT:anthropic-TEXT",
            "openai:ROUTING:openai-ROUTING",
        ), f.transport.calls)
        assertEquals(selection, f.preferences.ai)
    }

    @Test
    fun cloudIdOfAnotherRoleIsRejectedBeforeEveryOperation() = runTest {
        val wrong = AiSelection(
            speechToText = KashaAiCatalog.cloudEngineId("openai", AiRole.TEXT),
            text = KashaAiCatalog.cloudEngineId("openai", AiRole.ROUTING),
            routing = KashaAiCatalog.cloudEngineId("openai", AiRole.SPEECH_TO_TEXT),
        )
        val f = Fixture(wrong)
        for (operation in 0..3) {
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.invoke(operation)
            }.message)
        }
        assertTrue(f.transport.calls.isEmpty())
        assertEquals(wrong, f.preferences.ai)
    }

    @Test
    fun mismatchedCloudRoleIsNotReportedAsReady() = runTest {
        val wrong = AiSelection(
            speechToText = KashaAiCatalog.cloudEngineId("openai", AiRole.TEXT),
            text = KashaAiCatalog.cloudEngineId("openai", AiRole.ROUTING),
            routing = KashaAiCatalog.cloudEngineId("openai", AiRole.SPEECH_TO_TEXT),
        )
        val f = Fixture(wrong)
        val capabilities = f.router.capabilities(wrong, "ru")
        assertEquals(AiRole.entries.toSet(), capabilities.map { it.role }.toSet())
        capabilities.forEach {
            assertEquals(wrong.engineId(it.role), it.selectedEngineId)
            assertFalse(it.executable)
            assertEquals("platformUnavailable", it.reason)
        }
        assertTrue(f.transport.calls.isEmpty())
        assertEquals(wrong, f.preferences.ai)
    }

    @Test
    fun missingCloudDoesNotFallBackToLocalHandlers() = runTest {
        val f = Fixture(cloudSelection(), connected = false)
        for (operation in 0..3) {
            assertEquals("aiUnavailable", assertFailsWith<IllegalStateException> {
                f.invoke(operation)
            }.message)
        }
        assertTrue(f.transport.calls.isEmpty())
    }

    @Test
    fun cloudFailureDoesNotFallBackToLocalHandlers() = runTest {
        val f = Fixture(cloudSelection())
        val failure = IllegalStateException("testTransportFailure")
        f.transport.failure = failure
        for (operation in 0..3) {
            assertSame(failure, assertFailsWith<IllegalStateException> { f.invoke(operation) })
        }
        assertEquals(4, f.transport.calls.size)
    }

    @Test
    fun unavailableLocalSelectionIsPreservedByCapabilityCheck() = runTest {
        val selection = AiSelection()
        val f = Fixture(selection)
        val capabilities = f.router.capabilities(selection, "ru")
        capabilities.forEach {
            assertEquals(selection.engineId(it.role), it.selectedEngineId)
            assertFalse(it.executable)
            assertEquals("platformUnavailable", it.reason)
        }
        assertEquals(selection, f.preferences.ai)
        assertTrue(f.transport.calls.isEmpty())
    }

    private fun localIds(role: AiRole): List<String> = KashaAiCatalog.enginesFor(role)
        .filter { it.locality == AiLocality.LOCAL }.map { it.id }
        .also { assertTrue(it.isNotEmpty()) }

    private class Fixture(selection: AiSelection, connected: Boolean = true) {
        var preferences = Preferences(ai = selection)
        val transport = RecordingTransport()
        private val metadata = object : IosCloudMetadataStore {
            override fun read() = listOf(connection("openai"), connection("anthropic"))
            override fun write(value: List<CloudAiConnection>) = error("Неожиданная запись настроек")
        }
        private val secrets = object : IosSecretStore {
            override val available = true
            override fun get(providerId: String) = "test-only-key"
            override fun put(providerId: String, value: String) = error("Неожиданная запись ключа")
            override fun remove(providerId: String): SecureSecretDeletion = error("Неожиданное удаление ключа")
        }
        val router = IosRoutedIntelligence(
            IosOnDeviceIntelligence(),
            if (connected) IosCloudAiGateway(secrets, metadata, transport) else null,
        ) { preferences }

        suspend fun invoke(operation: Int) {
            when (operation) {
                0 -> router.transcribe("unused-audio", "ru", "")
                1 -> router.title(SOURCE, "ru")
                2 -> router.tidy(SOURCE, "ru")
                3 -> router.rank(SOURCE, PROJECTS, "ru")
                else -> error("Неизвестная проверяемая операция")
            }
        }
    }

    private class RecordingTransport : IosCloudTransport {
        val calls = mutableListOf<String>()
        var failure: IllegalStateException? = null
        override suspend fun test(connection: CloudAiConnection, apiKey: String) =
            error("Проверка соединения здесь не должна запускаться")
        override suspend fun generate(connection: CloudAiConnection, role: AiRole, apiKey: String, prompt: String): String {
            calls += "${connection.providerId}:${role.name}:${connection.modelFor(role)}"
            failure?.let { throw it }
            return if (role == AiRole.ROUTING) "{\"p\":3}" else SOURCE
        }
        override suspend fun transcribe(connection: CloudAiConnection, apiKey: String, file: String, language: String): String {
            val role = AiRole.SPEECH_TO_TEXT
            calls += "${connection.providerId}:${role.name}:${connection.modelFor(role)}"
            failure?.let { throw it }
            return SOURCE
        }
    }

    private companion object {
        const val SOURCE = "Ирина не меняла 1200 пунктов"
        val PROJECTS = listOf(Project("p", "Работа", instruction = "Ирина"))

        fun cloudSelection() = AiSelection(
            speechToText = KashaAiCatalog.cloudEngineId("openai", AiRole.SPEECH_TO_TEXT),
            text = KashaAiCatalog.cloudEngineId("anthropic", AiRole.TEXT),
            routing = KashaAiCatalog.cloudEngineId("openai", AiRole.ROUTING),
        )

        fun connection(provider: String): CloudAiConnection {
            val roles = assertNotNull(KashaAiCatalog.provider(provider)).roles
            val base = CloudAiConnection(
                providerId = provider,
                modelIds = roles.associateWith { "$provider-${it.name}" },
                enabled = true,
                privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
            )
            return base.copy(consentSnapshot = AiPrivacy.snapshot(base))
        }
    }
}
