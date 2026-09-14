package brain.domain

import brain.model.Project
import brain.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainTest {
    @Test
    fun pinnedProjectsAlwaysStayAboveAiRanking() {
        val pinned = Project("a", "Закреплённый", pinned = true, pinOrder = 0)
        val likely = Project("b", "Очень подходящий")
        val result = ProjectOrder.sorted(listOf(likely, pinned), mapOf("b" to 100, "a" to 0))
        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun unpinnedProjectsFollowRelevance() {
        val a = Project("a", "A")
        val b = Project("b", "B")
        assertEquals(listOf("b", "a"), ProjectOrder.sorted(listOf(a, b), mapOf("a" to 20, "b" to 80)).map { it.id })
    }

    @Test
    fun appendNeverRewritesExistingText() {
        val old = "Старый текст"
        val result = NoteText.append(old, "Новая мысль")
        assertTrue(result.startsWith(old))
        assertEquals("Старый текст\n\nНовая мысль", result)
    }

    @Test
    fun pinningNoteDoesNotChangeItsText() {
        val note = Note("n", "p", "Заголовок", "Текст", 1, 2)
        val data = BrainData(projects = listOf(Project("p", "Проект")), notes = listOf(note))
        val pinned = data.pinNote("n", true).notes.single()
        assertTrue(pinned.pinned)
        assertEquals("Заголовок", pinned.title)
        assertEquals("Текст", pinned.body)
        assertEquals(2, pinned.updatedAt)
    }
}
