package brain.studio

import kotlin.test.*

class ReorderPresentationTest {
    private data class Row(val id: String, val text: String)
    private val initial = listOf(Row("a", "Первый"), Row("b", "Второй"), Row("c", "Третий"))
    @Test fun latestContentsWithUnchangedIdsAreRendered() {
        val changed = initial.map { if (it.id == "a") it.copy(text = "Новая редакция") else it }
        assertEquals(changed, reorderPreview(changed, null, Row::id))
        assertEquals("Новая редакция", reorderPreview(changed, listOf("b", "a", "c"), Row::id)[1].text)
    }
    @Test fun failedSaveOrCancelledDragReturnsToAuthoritativeOrder() {
        val preview = movedItemKeys(initial.map(Row::id), "a", 1)!!
        assertEquals(listOf("b", "a", "c"), reorderPreview(initial, preview, Row::id).map(Row::id))
        assertEquals(initial, reorderPreview(initial, null, Row::id))
    }
    @Test fun addedOrRemovedRowsNeverDisappearInAnOldPreview() {
        assertEquals(initial, reorderPreview(initial, listOf("b", "a"), Row::id))
        assertEquals(initial, reorderPreview(initial, listOf("b", "b", "c"), Row::id))
        assertEquals(initial, reorderPreview(initial, listOf("a", "b", "foreign"), Row::id))
    }
    @Test fun keyboardAndAccessibilityUseExactIdAndKeepAllRows() {
        assertEquals(listOf("b", "a", "c"), movedItemKeys(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "c", "b"), movedItemKeys(listOf("a", "b", "c"), "b", 1))
        assertNull(movedItemKeys(listOf("a", "b"), "a", -1))
        assertNull(movedItemKeys(listOf("a", "b"), "b", 1))
        assertNull(movedItemKeys(listOf("a", "b"), "absent", 1))
        assertNull(movedItemKeys(listOf("a", "b"), "a", 0))
    }
    @Test fun externalOrderOrMembershipChangeCancelsDragCommit() {
        assertNull(reorderCommit(listOf("a", "b"), listOf("b", "a"), listOf("b", "a")))
        assertNull(reorderCommit(listOf("a", "b"), listOf("a", "b", "c"), listOf("b", "a")))
        assertNull(reorderCommit(listOf("a", "b"), listOf("a", "b"), listOf("b", "b")))
    }
    @Test fun validDragSubmitsOnceAndUnchangedDragDoesNothing() {
        assertEquals(listOf("b", "a"), reorderCommit(listOf("a", "b"), listOf("a", "b"), listOf("b", "a")))
        assertNull(reorderCommit(listOf("a", "b"), listOf("a", "b"), listOf("a", "b")))
        assertNull(reorderCommit(null, listOf("a", "b"), null))
    }
}
