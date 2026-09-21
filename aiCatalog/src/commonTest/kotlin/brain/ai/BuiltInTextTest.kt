package brain.ai

import brain.model.Project
import brain.studio.*
import kotlin.test.*

class BuiltInTextTest {
    @Test fun formattingPreservesNamesNumbersAndNegation() {
        assertEquals("Ирина не меняла 1200\n\nпунктов", BuiltInText.tidy("  Ирина  не меняла 1200\n\n\nпунктов ", "ru"))
    }
    @Test fun matchingUsesTheSameProjectInstructionForAllMobileAdapters() {
        val projects = listOf(Project("p", "Работа", instruction = "Обсуждаем интерфейс"), Project("q", "Дом"))
        assertEquals(mapOf("p" to 1, "q" to 0), BuiltInText.rank("Поправить интерфейс", projects, "ru"))
    }
    @Test fun mobileSpeechIdentifiersCannotStandForEachOtherOrWhisper() {
        assertFalse(BuiltInAi.supportsAndroid(AiRole.SPEECH_TO_TEXT, BuiltInAi.APPLE_SPEECH))
        assertFalse(BuiltInAi.supportsApple(AiRole.SPEECH_TO_TEXT, BuiltInAi.ANDROID_SPEECH))
        assertFalse(BuiltInAi.supportsAndroid(AiRole.SPEECH_TO_TEXT, AiSelection.DEFAULT_STT))
        assertTrue(BuiltInAi.supportsAndroid(AiRole.SPEECH_TO_TEXT, BuiltInAi.ANDROID_SPEECH))
    }
    @Test fun newAndroidDefaultsDescribeRealNativeHandlers() {
        val selection = KashaAiCatalog.validateSelection(BuiltInAi.androidSelection())
        assertEquals(BuiltInAi.ANDROID_SPEECH, selection.speechToText)
        assertEquals(BuiltInAi.LOCAL_RULES, selection.text)
        assertEquals(BuiltInAi.LOCAL_RULES, selection.routing)
    }
}
