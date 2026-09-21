package brain.ai

import brain.studio.*
import kotlin.test.*

class BuiltInAiTest {
    @Test fun nativeDefaultNamesRealHandlers() {
        val selected = KashaAiCatalog.validateSelection(BuiltInAi.appleSelection())
        assertEquals(BuiltInAi.APPLE_SPEECH, selected.speechToText)
        assertEquals(BuiltInAi.LOCAL_RULES, selected.text)
        assertEquals(BuiltInAi.LOCAL_RULES, selected.routing)
        assertEquals(AiLocality.NATIVE, KashaAiCatalog.engine(selected.speechToText)?.locality)
    }
    @Test fun whisperSelectionCannotSilentlyExecuteAppleSpeech() {
        assertFailsWith<IllegalStateException> { BuiltInAi.requireApple(AiRole.SPEECH_TO_TEXT, AiSelection.DEFAULT_STT) }
    }
    @Test fun qwenSelectionCannotSilentlyExecuteTextRules() {
        assertFailsWith<IllegalStateException> { BuiltInAi.requireApple(AiRole.TEXT, AiSelection.DEFAULT_TEXT) }
        assertFailsWith<IllegalStateException> { BuiltInAi.requireApple(AiRole.ROUTING, AiSelection.DEFAULT_ROUTING) }
    }
    @Test fun nativeHandlerCannotBeUsedForAnotherRole() {
        assertFailsWith<IllegalStateException> { BuiltInAi.requireApple(AiRole.TEXT, BuiltInAi.APPLE_SPEECH) }
        assertFailsWith<IllegalStateException> { BuiltInAi.requireApple(AiRole.SPEECH_TO_TEXT, BuiltInAi.LOCAL_RULES) }
    }
    @Test fun ordinaryDefaultsAndStoredSelectionAreNotRewritten() {
        val stored = AiSelection(speechToText = "local.whisper.medium")
        assertEquals(stored, KashaAiCatalog.validateSelection(stored))
        assertEquals(AiSelection.DEFAULT_STT, AiSelection().speechToText)
    }
}
