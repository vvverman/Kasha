package brain.domain

import kotlin.test.*

class TranscriptSentenceBoundaryTest {
    @Test fun digitAtTheEndIsNotAnInitialOrAListMarker() {
        val source = "We meet on Friday at 3. Do not delete the old notes."
        val parts = TranscriptCleanup.parts(source)
        assertEquals(2, parts.size)
        assertEquals("We meet on Friday at 3.", parts.first().source)
        assertEquals(source, parts.joinToString("") { it.source + it.separator })
    }
    @Test fun numberAtTheBeginningStillBelongsToTheListItem() {
        val source = "1. Проверить 12 файлов. 2. Сохранить отчёт."
        assertEquals(listOf("1. Проверить 12 файлов.", "2. Сохранить отчёт."), TranscriptCleanup.parts(source).map { it.source })
    }
    @Test fun decimalAmountAtTheEndStillEndsTheSentence() {
        assertEquals(2, TranscriptCleanup.parts("Стоимость 12.50. Налог включён.").size)
        assertEquals(2, TranscriptCleanup.parts("Скидка 5. Налог включён.").size)
    }
}
