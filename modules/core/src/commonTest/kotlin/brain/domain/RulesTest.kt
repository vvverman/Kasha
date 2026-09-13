package brain.domain

import brain.model.*
import kotlin.test.*

class RulesTest {
    private val project = Project("p", "Проект")
    private fun data() = BrainData(projects = listOf(project), captures = listOf(Capture("c", 0, transcript = "Новая мысль", status = CaptureStatus.READY)))
    @Test fun originalWhitespaceIsNotRewritten() { assertEquals("  Старый\n \n\nНовый", NoteText.append("  Старый\n ", "Новый")) }
    @Test fun repeatedDistributionKeepsOneSource() {
        val (first, note) = data().distribute("c", DistributionRequest("p"), "n", 1)
        val (again, same) = first.distribute("c", DistributionRequest("p"), "n2", 2)
        assertEquals(first, again); assertEquals(note, same); assertEquals(1, again.notes.size)
    }
    @Test fun foreignNoteIsRejected() {
        val d = data().copy(notes = listOf(Note("n", "other", "T", "old", 0, 0)))
        assertFails { d.distribute("c", DistributionRequest("p", "n"), "n2", 1) }; assertEquals("old", d.notes.single().body)
    }
    @Test fun workingCaptureCannotBeMoved() { assertFails { data().copy(captures = listOf(Capture("c", 0, transcript = "x"))).distribute("c", DistributionRequest("p"), "n", 0) } }
    @Test fun missingProjectCannotBeCreatedByDistribution() { assertFails { data().distribute("c", DistributionRequest("missing"), "n", 1) } }
    @Test fun draftClearsObsoleteRank() {
        val d = data().copy(captures = listOf(data().captures.single().copy(rankingApplied = true, relevance = mapOf("p" to 4))))
        val changed = d.updateDraft("c", CaptureDraftUpdate("Заголовок", "Изменено")).captures.single()
        assertFalse(changed.rankingApplied); assertTrue(changed.draftEdited); assertTrue(changed.relevance.isEmpty())
    }
    @Test fun emptyEditedTextDoesNotRevertToTranscript() { assertEquals("", data().updateDraft("c", CaptureDraftUpdate("", "")).captures.single().textToSave) }
    @Test fun repeatedPinKeepsOrder() { val d = data().pinProject("p", true); assertEquals(d, d.pinProject("p", true)) }
    @Test fun manualPinsMustBeCompleteAndUnique() {
        val d = BrainData(projects = listOf(Project("a", "A", pinned = true), Project("b", "B", pinned = true, pinOrder = 1)))
        assertFails { d.orderPins(listOf("a", "a")) }; assertFails { d.orderPins(listOf("b")) }
        assertEquals(listOf("b", "a"), ProjectOrder.sorted(d.orderPins(listOf("b", "a")).projects).map { it.id })
    }
    @Test fun duplicateCaptureIsNotAnUpdate() { assertFails { data().addCapture(data().captures.single()) } }
    @Test fun chunksKeepUnicodeAndTheTail() {
        val text = ("Русский текст, отрицание не терять. 🧠\n".repeat(300)) + "ПОСЛЕДНЕЕ"
        val parts = TextChunks.split(text, 83)
        assertEquals(text, parts.joinToString("")); assertTrue(parts.all { it.length <= 83 }); assertTrue(parts.last().endsWith("ПОСЛЕДНЕЕ"))
    }
    @Test fun emptyChunks() { assertTrue(TextChunks.split("").isEmpty()) }
    @Test fun silenceIsConservative() { assertEquals(listOf(AudioSpan(0.0, 10.0, 0.0)), SilencePlanner.spans(List(100) { -90.0 }, .1, 10.0)) }
    @Test fun longGapIsRemovedAndTimeMapped() {
        val levels = List(100) { if (it < 10 || it > 85) -20.0 else -90.0 }
        val spans = SilencePlanner.spans(levels, .1, 10.0)
        assertEquals(2, spans.size); assertTrue(spans.sumOf { it.duration } < 5)
        assertEquals(spans[1].compactStart, AudioTimeline.compactTime(4.0, spans))
    }
    @Test fun malformedModelOutputIsNotSuccess() {
        assertFails { ModelOutput.cleaned("not json", "Полный текст") }
        assertFails { ModelOutput.cleaned("""{"title":"T","text":"x"}""", "Длинный полный текст который нельзя сокращать") }
        assertFails { ModelOutput.relevance("""{"relevance":90}""") }
    }
    @Test fun whisperMillisecondsConvertToSeconds() {
        val pieces = ModelOutput.whisperPieces("""{"transcription":[{"offsets":{"from":1500,"to":2000},"text":"Привет"},{"offsets":{"from":-1,"to":3},"text":"ошибка"}]}""", 3.0)
        assertEquals(1, pieces.size); assertEquals(1.5, pieces.single().start)
    }
}
