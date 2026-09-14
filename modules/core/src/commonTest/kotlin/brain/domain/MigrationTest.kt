package brain.domain

import brain.model.Note
import brain.model.Project
import brain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    @Test
    fun legacyArrayOrderBecomesInitialManualOrder() {
        val legacy = BrainData(
            projects = listOf(
                Project("p2", "Второй", createdAt = 20),
                Project("p1", "Первый", createdAt = 10),
            ),
            notes = listOf(
                Note("n2", "p2", "Два", "", 20, 20),
                Note("n1", "p2", "Один", "", 10, 10),
            ),
            tasks = listOf(
                Task("t2", "p2", "Два", 20, 20),
                Task("t1", "p2", "Один", 10, 10),
            ),
        )

        val migrated = legacy.migrated()

        assertEquals(listOf("p2", "p1"), UserSort.projects(migrated.projects, brain.model.SortMode.MANUAL).map { it.id })
        assertEquals(listOf("n2", "n1"), UserSort.notes(migrated.notes, brain.model.SortMode.MANUAL).map { it.id })
        assertEquals(listOf("t2", "t1"), UserSort.tasks(migrated.tasks, brain.model.SortMode.MANUAL).map { it.id })
        assertEquals(20, migrated.projects.first().updatedAt)
        assertEquals(10, migrated.projects.last().updatedAt)
    }
}
