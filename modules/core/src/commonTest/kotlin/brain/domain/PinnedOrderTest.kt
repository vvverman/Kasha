package brain.domain

import brain.model.Note
import brain.model.Project
import brain.model.SortMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PinnedOrderTest {
    @Test
    fun manualProjectsKeepPinnedGroupOnTopWithoutChangingManualOrder() {
        val projects = listOf(
            Project("p1", "Первый", pinned = true, pinOrder = 1, manualOrder = 0),
            Project("p2", "Второй", pinned = false, manualOrder = 1),
            Project("p3", "Третий", pinned = true, pinOrder = 0, manualOrder = 2),
        )

        assertEquals(
            listOf("p3", "p1", "p2"),
            UserSort.projects(projects, SortMode.MANUAL).map { it.id },
        )
        assertEquals(listOf(0, 1, 2), projects.map { it.manualOrder })
    }

    @Test
    fun manualNotesKeepPinnedGroupOnTopWithoutChangingManualOrder() {
        val notes = listOf(
            Note("n1", "p", "", "Первая", 1, 1, pinned = false, manualOrder = 0),
            Note("n2", "p", "", "Вторая", 1, 1, pinned = true, pinOrder = 1, manualOrder = 1),
            Note("n3", "p", "", "Третья", 1, 1, pinned = true, pinOrder = 0, manualOrder = 2),
        )

        assertEquals(
            listOf("n3", "n2", "n1"),
            UserSort.notes(notes, SortMode.MANUAL).map { it.id },
        )
        assertEquals(listOf(0, 1, 2), notes.map { it.manualOrder })
    }

    @Test
    fun orderingNotePinsTouchesOnlyPinnedNotesOfSelectedProject() {
        val projects = listOf(Project("p", "P"), Project("other", "Other"))
        val notes = listOf(
            Note("a", "p", "", "A", 1, 1, pinned = true, pinOrder = 0, manualOrder = 2),
            Note("b", "p", "", "B", 1, 1, pinned = true, pinOrder = 1, manualOrder = 0),
            Note("c", "p", "", "C", 1, 1, pinned = false, pinOrder = 7, manualOrder = 1),
            Note("x", "other", "", "X", 1, 1, pinned = true, pinOrder = 5, manualOrder = 0),
        )

        val changed = BrainData(projects = projects, notes = notes).orderNotePins("p", listOf("b", "a"))

        assertEquals(1, changed.notes.first { it.id == "a" }.pinOrder)
        assertEquals(0, changed.notes.first { it.id == "b" }.pinOrder)
        assertEquals(7, changed.notes.first { it.id == "c" }.pinOrder)
        assertEquals(5, changed.notes.first { it.id == "x" }.pinOrder)
        assertEquals(notes.map { it.manualOrder }, changed.notes.map { it.manualOrder })
    }

    @Test
    fun orderingNotePinsRequiresEveryPinnedNoteExactlyOnce() {
        val data = BrainData(
            projects = listOf(Project("p", "P")),
            notes = listOf(
                Note("a", "p", "", "A", 1, 1, pinned = true),
                Note("b", "p", "", "B", 1, 1, pinned = true),
            ),
        )

        assertFails { data.orderNotePins("p", listOf("a")) }
        assertFails { data.orderNotePins("p", listOf("a", "a")) }
    }
}
