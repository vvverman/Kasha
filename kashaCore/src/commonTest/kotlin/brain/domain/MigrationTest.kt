package brain.domain

import brain.model.Note
import brain.model.Project
import brain.model.SortMode
import brain.model.Task
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test
    fun activeManualOrderSurvivesReloadWithArchivedOrderCollision() {
        val saved = BrainData(tasks = listOf(
            Task("a", text = "А", createdAt = 1, updatedAt = 1, manualOrder = 0),
            Task("b", text = "Б", createdAt = 2, updatedAt = 2, manualOrder = 1),
            Task("c", text = "В", createdAt = 3, updatedAt = 3, manualOrder = 2),
        )).completeTask("b", 4).orderTasks(listOf("c", "a"))

        val loaded = Json.decodeFromString<BrainData>(Json.encodeToString(saved)).migrated()

        assertEquals(listOf("c", "a"), SnapshotQueries.tasks(loaded.tasks, SortMode.MANUAL).map { it.id })
        assertEquals(saved, loaded)
        assertEquals(loaded, loaded.migrated())
    }

    @Test
    fun legacyActiveOrderRepairDoesNotRewriteArchive() {
        val archived = Task("done", text = "Выполнено", createdAt = 1, updatedAt = 2,
            manualOrder = 0, completedAt = 2)
        val legacy = BrainData(tasks = listOf(
            Task("b", text = "Б", createdAt = 3, updatedAt = 3),
            archived,
            Task("a", text = "А", createdAt = 4, updatedAt = 4),
        ))

        val migrated = legacy.migrated()

        assertEquals(listOf("b", "a"), SnapshotQueries.tasks(migrated.tasks, SortMode.MANUAL).map { it.id })
        assertEquals(listOf(0, 1), migrated.tasks.filterNot { it.completed }.map { it.manualOrder })
        assertEquals(archived, migrated.tasks.single { it.id == archived.id })
        assertEquals(migrated, migrated.migrated())
    }
}
