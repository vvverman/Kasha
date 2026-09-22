package brain.domain

import kotlin.test.*

class CleanupSafetyRegressionTest {
    @Test fun emptyProtocolReasoningIsNotPartOfTheNote() {
        assertEquals("Встреча в среду.", LocalModelText.cleanupPayload("<think>\n\n</think>\n\nВстреча в среду. [end of text]\n"))
        assertEquals("Встреча в среду.", LocalModelText.cleanupPayload("assistant/final\nВстреча в среду."))
        assertEquals("Встреча в среду.", LocalModelText.cleanupPayload("Встреча в среду."))
    }

    @Test fun nonemptyOrIncompleteReasoningIsRejected() {
        assertFails { LocalModelText.cleanupPayload("<think>Нужно ответить</think>Встреча в среду.") }
        assertFails { LocalModelText.cleanupPayload("<think>Незавершённый ответ") }
        assertFails { LocalModelText.cleanupPayload("assistant/analysis\nПлан ответа") }
    }

    @Test fun recordedCleanupAnswerCannotLoseTheFinalCorrection() {
        val source = "Эээ, встреча завтра, нет, в среду в три часа."
        val actualOutput = "<think>\n\n</think>\n\nЭэ, встреча завтра, в три часа. [end of text]\n\n\n"
        assertFails { LocalModelText.requirePreserved(source, LocalModelText.cleanupPayload(actualOutput)) }
        LocalModelText.requirePreserved(source, "Встреча в среду в три часа.")
    }

    @Test fun finalCorrectedNumberAndNameMustRemain() {
        val numbers = "Встреча в 2, нет, точнее в 3 часа."
        assertFails { LocalModelText.requirePreserved(numbers, "Встреча в 2 часа.") }
        LocalModelText.requirePreserved(numbers, "Встреча в 3 часа.")
        val names = "Позвонить Ивану, нет, точнее Петру завтра."
        assertFails { LocalModelText.requirePreserved(names, "Позвонить Ивану завтра.") }
        LocalModelText.requirePreserved(names, "Позвонить Петру завтра.")
    }

    @Test fun recordedCleanupAnswerCannotDropTheProhibitionSentence() {
        val source = "В проекте приложения нужно исправить запись голоса. Добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя."
        val actualOutput = "<think>\n\n</think>\n\nВ проекте приложения нужно исправить запись голоса. Добавить кнопку пауза и проверить сохранение заметок. [end of text]\n\n\n"
        assertFails { LocalModelText.requirePreserved(source, LocalModelText.cleanupPayload(actualOutput)) }
        LocalModelText.requirePreserved(source, source.replace(". ", ".\n\n"))
    }
}
