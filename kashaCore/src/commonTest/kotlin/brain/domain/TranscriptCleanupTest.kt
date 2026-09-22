package brain.domain

import kotlin.test.*

class TranscriptCleanupTest {
    @Test fun explicitDatesAndAmountsCopyTheFinalValue() {
        assertEquals("встреча в среду в три часа.", TranscriptCleanup.prepare("Эээ, встреча завтра, нет, в среду в три часа."))
        assertEquals("Так, Марина получит 2800 рублей.", TranscriptCleanup.prepare("Так, Марина получит 2400 рублей, нет, 2800 рублей."))
        assertEquals("Встреча в 3 часа.", TranscriptCleanup.prepare("Встреча в 2, нет, точнее в 3 часа."))
        assertEquals("Um, we meet on Friday at 3.", TranscriptCleanup.prepare("Um, we meet on Monday, no, on Friday at 3."))
        assertEquals("Встреча в пятницу.", TranscriptCleanup.prepare("Встреча завтра, нет, в среду, нет, в пятницу."))
    }
    @Test fun negativeStatementsQuotesTimesAndDifferentUnitsAreNotGuessed() {
        for (text in listOf("Um 12 Uhr beginnt das Treffen.", "Не удалять 1200 записей.", "Встреча в 15:30, нет, в 16:40.",
            "Заплатить 10 рублей, нет, 12 долларов.", "Цитата: «Встреча завтра, нет, в среду».",
            "Номер 12-30, нет, 14-40.", "Ссылка https://example.invalid/12, нет, 14.")) {
            assertEquals(text, TranscriptCleanup.prepare(text), text)
        }
    }
    @Test fun sourcesAndSeparatorsRoundTripExactly() {
        val text = "Нужна запись голоса. Добавить кнопку паузы.\n\nСтарый текст удалять нельзя."
        val parts = TranscriptCleanup.parts(text)
        assertEquals(3, parts.size)
        assertEquals(text, parts.joinToString("") { it.source + it.separator })
    }
    @Test fun initialsAbbreviationsQuotesAndCorrectionStayTogether() {
        assertEquals(2, TranscriptCleanup.parts("А. Б. Иванов живёт в г. Москве. Позвонить завтра.").size)
        assertEquals(1, TranscriptCleanup.parts("Встреча завтра. Нет, в среду.").size)
        assertEquals(2, TranscriptCleanup.parts("Фраза «Не удалять. Сохранить всё». Позвонить завтра.").size)
        assertEquals(2, TranscriptCleanup.parts("Это дом. Позвонить завтра.").size)
    }
    @Test fun finalValueAndUnrelatedNumbersCannotDisappear() {
        val source = "Проверить 12 файлов. Встреча завтра, нет, в среду в 3 часа."
        LocalModelText.requirePreserved(source, "Проверить 12 файлов. Встреча в среду в 3 часа.")
        assertFails { LocalModelText.requirePreserved(source, "Проверить файлы. Встреча в среду в 3 часа.") }
        assertFails { LocalModelText.requirePreserved(source, "Проверить 12 файлов. Встреча завтра в среду в 3 часа.") }
        LocalModelText.requirePreserved("We meet on Monday, no, on Friday at 3. Do not delete notes.", "We meet on Friday at 3. Do not delete notes.")
        assertFails { LocalModelText.requirePreserved("No entries are available.", "Entries are available.") }
    }
    @Test fun nonlexicalHesitationIsNotProtectedAsAPersonName() {
        LocalModelText.requirePreserved("Эээ, Ирина не удаляла 12 записей.", "Ирина не удаляла 12 записей.")
        assertFails { LocalModelText.requirePreserved("Эээ, Ирина не удаляла 12 записей.", "Анна не удаляла 12 записей.") }
    }
}
