package brain.studio

import brain.model.Project
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiEnginesTest {
    @Test
    fun defaultSelectionIsOpaqueAndValid() {
        val selection = AiSelection().validated()
        assertTrue(selection.speechToText.isNotBlank())
        assertTrue(selection.text.isNotBlank())
        assertTrue(selection.routing.isNotBlank())
        assertTrue("whisper" !in selection.speechToText.lowercase())
        assertTrue("qwen" !in selection.text.lowercase())
    }

    @Test
    fun malformedSelectionIsRejectedByCore() {
        assertFails { AiSelection(text = " ").validated() }
    }

    @Test
    fun externalPrivacyIsExplicitPerRole() {
        assertEquals(setOf(AiDataKind.AUDIO), AiPrivacy.dataFor(AiRole.SPEECH_TO_TEXT))
        assertEquals(setOf(AiDataKind.NOTE_TEXT), AiPrivacy.dataFor(AiRole.TEXT))
        assertEquals(
            setOf(
                AiDataKind.NOTE_TEXT,
                AiDataKind.PROJECT_IDS,
                AiDataKind.PROJECT_TITLES,
                AiDataKind.PROJECT_DESCRIPTIONS,
                AiDataKind.PROJECT_INSTRUCTIONS,
            ),
            AiPrivacy.dataFor(AiRole.ROUTING),
        )
    }

    @Test
    fun combinedExternalPrivacyIsUnionOfSelectedRoles() {
        assertEquals(
            setOf(
                AiDataKind.AUDIO,
                AiDataKind.NOTE_TEXT,
                AiDataKind.PROJECT_IDS,
                AiDataKind.PROJECT_TITLES,
                AiDataKind.PROJECT_DESCRIPTIONS,
                AiDataKind.PROJECT_INSTRUCTIONS,
            ),
            AiPrivacy.dataFor(setOf(AiRole.SPEECH_TO_TEXT, AiRole.ROUTING)),
        )
    }

    @Test
    fun routingPayloadExpansionInvalidatesLegacyConsent() {
        assertEquals(2, AiPrivacy.CONSENT_VERSION)
        val oldConnection = CloudAiConnection(
            providerId = "provider",
            modelIds = mapOf(AiRole.ROUTING to "model"),
            enabled = true,
            privacyConsentVersion = 1,
        )
        assertTrue(oldConnection.privacyConsentVersion < AiPrivacy.CONSENT_VERSION)
        assertFalse(AiPrivacy.hasCurrentConsent(oldConnection))
    }

    @Test
    fun consentSnapshotBindsProviderEndpointModelsRolesAndDataKinds() {
        val base = CloudAiConnection(
            providerId = "provider",
            modelIds = mapOf(
                AiRole.TEXT to " text-model ",
                AiRole.ROUTING to "route-model",
            ),
            endpoint = "https://example.com/v1/",
            enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
        )
        val snapshot = AiPrivacy.snapshot(base)
        val consented = base.copy(consentSnapshot = snapshot)

        assertEquals("provider", snapshot.providerId)
        assertEquals("https://example.com/v1", snapshot.normalizedEndpoint)
        assertEquals("example.com", snapshot.endpointHost)
        assertEquals(setOf(AiRole.TEXT, AiRole.ROUTING), snapshot.roles)
        assertEquals("text-model", snapshot.modelIds[AiRole.TEXT])
        assertEquals(AiPrivacy.dataFor(snapshot.roles), snapshot.dataKinds)
        assertTrue(AiPrivacy.hasCurrentConsent(consented))
    }

    @Test
    fun changingEndpointOrModelInvalidatesConsentSnapshot() {
        val base = CloudAiConnection(
            providerId = "provider",
            modelIds = mapOf(AiRole.TEXT to "model-a"),
            endpoint = "https://example.com/v1",
            enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
        )
        val consented = base.copy(consentSnapshot = AiPrivacy.snapshot(base))

        assertTrue(AiPrivacy.hasCurrentConsent(consented))
        assertFalse(AiPrivacy.hasCurrentConsent(consented.copy(endpoint = "https://other.example/v1")))
        assertFalse(AiPrivacy.hasCurrentConsent(consented.copy(modelIds = mapOf(AiRole.TEXT to "model-b"))))
        assertFalse(AiPrivacy.hasCurrentConsent(consented.copy(modelIds = mapOf(AiRole.ROUTING to "model-a"))))
    }

    @Test
    fun compositeUsesThreeIndependentRolePorts() = runTest {
        val calls = mutableListOf<String>()
        val speech = object : SpeechToTextEngine {
            override val descriptor = descriptor("speech", AiRole.SPEECH_TO_TEXT)
            override suspend fun transcribe(file: String, language: String): String {
                calls += "speech"
                return "transcript"
            }
        }
        val text = object : TextProcessingEngine {
            override val descriptor = descriptor("text", AiRole.TEXT)
            override suspend fun title(text: String, language: String): String {
                calls += "title"
                return "title"
            }
            override suspend fun tidy(text: String, language: String): String {
                calls += "tidy"
                return "tidy"
            }
        }
        val routing = object : RoutingEngine {
            override val descriptor = descriptor("routing", AiRole.ROUTING)
            override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
                calls += "routing"
                return mapOf("p" to 4)
            }
        }

        val intelligence = CompositeIntelligence(speech, text, routing)
        assertEquals("transcript", intelligence.transcribe("a.wav", "ru", ""))
        assertEquals("title", intelligence.title("source", "ru"))
        assertEquals("tidy", intelligence.tidy("source", "ru"))
        assertEquals(mapOf("p" to 4), intelligence.rank("source", emptyList(), "ru"))
        assertEquals(listOf("speech", "title", "tidy", "routing"), calls)
    }

    private fun descriptor(id: String, role: AiRole) = AiEngineDescriptor(
        id = id,
        name = id,
        provider = "test",
        roles = setOf(role),
        locality = AiLocality.LOCAL,
    )
}
