package brain.domain

import brain.model.*
import kotlin.test.*

class CaptureAiResultTest {
    private val initial = Capture("voice", 1, transcript = "Исходник", preparedText = "Исходник", status = CaptureStatus.READY)
    @Test fun unchangedInputAcceptsResult() {
        val result = initial.copy(preparedText = "Обработано", llmApplied = true)
        assertEquals(result, CaptureAiResult.apply(initial, initial, result))
    }
    @Test fun newerEditWins() {
        val edited = initial.copy(preparedText = "Новая редакция", draftEdited = true)
        assertEquals(edited, CaptureAiResult.apply(initial, edited, initial.copy(preparedText = "Поздний ответ")))
    }
    @Test fun finishedOperationDoesNotLeaveNewerEditInWorkingState() {
        val started = initial.copy(status = CaptureStatus.POLISHING)
        val edited = started.copy(preparedText = "Новая редакция", draftEdited = true)
        val result = initial.copy(preparedText = "Поздний ответ", llmApplied = true)
        assertEquals(edited.copy(status = CaptureStatus.READY), CaptureAiResult.apply(started, edited, result))
    }
    @Test fun anotherPhaseIsNotFinishedByOldAnswer() {
        val started = initial.copy(status = CaptureStatus.POLISHING)
        val current = initial.copy(status = CaptureStatus.TRANSCRIBING)
        assertEquals(current, CaptureAiResult.apply(started, current, initial))
    }
    @Test fun otherCaptureCannotBeOverwritten() {
        val other = initial.copy(id = "other")
        assertEquals(other, CaptureAiResult.apply(initial, other, initial))
    }
    @Test fun distributedSourceCannotBeChanged() {
        val saved = initial.copy(noteId = "note")
        assertEquals(saved, CaptureAiResult.apply(initial, saved, initial))
    }
    @Test fun lateRoutingCannotEraseNewRanking() {
        val current = initial.copy(relevance = mapOf("project" to 1), rankingApplied = true)
        assertEquals(current, CaptureAiResult.apply(initial, current, initial.copy(relevance = mapOf("project" to 4))))
    }
    @Test fun foreignResultIsRejected() {
        assertFailsWith<IllegalArgumentException> { CaptureAiResult.apply(initial, initial, initial.copy(id = "other")) }
    }
}
