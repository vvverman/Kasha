package brain.domain

import kotlin.test.*

class CleanupCopyEditsTest {
    @Test fun adjacentRepeatedListItemKeepsOneCopy() {
        assertEquals("Ну, мне нужно купить молоко и хлеб.", TranscriptCleanup.prepare("Ну, мне нужно купить молоко, молоко и хлеб."))
        assertEquals("Milk and bread.", TranscriptCleanup.prepare("Milk, milk and bread."))
        assertEquals("Очень, очень и очень хорошо.", TranscriptCleanup.prepare("Очень, очень и очень хорошо."))
        assertEquals("Цитата «молоко, молоко и хлеб».", TranscriptCleanup.prepare("Цитата «молоко, молоко и хлеб»."))
    }
    @Test fun evenOneMissingContentWordInAShortLineIsRejected() {
        assertFails { LocalModelText.requireCleanupCoverage("Нужно купить молоко и хлеб.", "Нужно купить хлеб.") }
        LocalModelText.requireCleanupCoverage("Нужно купить молоко и хлеб.", "Нужно купить молоко и хлеб.")
    }
    @Test fun onlyEquivalentNumberSpellingWithMatchingContextIsRestored() {
        assertEquals("We meet on Friday at 3.", TranscriptCleanup.restoreNumberSpelling("Um, we meet on Friday at 3.", "We meet on Friday at three."))
        assertEquals("I need 12 files and 20 notes.", TranscriptCleanup.restoreNumberSpelling("I need 12 files and 20 notes.", "I need twelve files and twenty notes."))
        assertEquals("We meet at four.", TranscriptCleanup.restoreNumberSpelling("We meet at 3.", "We meet at four."))
        assertEquals("Delete three files.", TranscriptCleanup.restoreNumberSpelling("Do not delete 3 files.", "Delete three files."))
    }
    @Test fun signsCodesAndUnitsAreNotGuessed() {
        for ((source, result) in listOf("Code 003." to "Code three.", "Value -3." to "Value three.",
            "Path /3." to "Path three.", "Value 3%." to "Value three.", "Value 3.5." to "Value three.")) {
            assertEquals(result, TranscriptCleanup.restoreNumberSpelling(source, result))
            assertFails { LocalModelText.requirePreserved(source, result) }
        }
        assertFails { LocalModelText.requirePreserved("Значение -3.", "Значение 3.") }
        assertFails { LocalModelText.requirePreserved("Значение +3.", "Значение -3.") }
    }
}
