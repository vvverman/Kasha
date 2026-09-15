package brain.ai

import brain.ai.external.ExternalAiProtocol
import brain.studio.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ExternalProtocolPrivacyTest {
    private fun connection(): CloudAiConnection {
        val draft = CloudAiConnection(providerId = "openai", modelIds = mapOf(AiRole.TEXT to "selected-model"),
            enabled = true, privacyConsentVersion = AiPrivacy.CONSENT_VERSION)
        return draft.copy(consentSnapshot = AiPrivacy.snapshot(draft))
    }
    @Test fun responseIsNotStoredAsRemoteConversation() {
        val request = ExternalAiProtocol.generate(connection(), AiRole.TEXT, "PRIVATE-KEY", "note text")
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(JsonPrimitive(false), json["store"])
        assertEquals(JsonPrimitive("selected-model"), json["model"])
        assertFalse(request.body!!.contains("PRIVATE-KEY"))
        assertFalse(json.containsKey("conversation"))
    }
    @Test fun incompleteAndFailedResponsesCannotReplaceUserText() {
        for (state in listOf("incomplete", "failed", "cancelled", "in_progress")) {
            assertFails { ExternalAiProtocol.text("openai", """{"status":"$state","output_text":"partial text"}""") }
        }
        assertFails { ExternalAiProtocol.text("openai", """{"error":{"message":"error"},"output_text":"partial"}""") }
        assertEquals("complete text", ExternalAiProtocol.text("openai", """{"status":"completed","error":null,"output_text":"complete text"}"""))
    }
}
