package brain.studio

import brain.domain.*
import brain.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskLifecycleStateTest {
    private class Repo : StudioRepository {
        override val simulated = true
        var data = BrainData(
            projects = listOf(Project("p", "Проект")),
            tasks = listOf(
                Task(
                    id = "t",
                    text = "Старый текст",
                    createdAt = 1,
                    updatedAt = 1,
                    dueAt = 10_000,
                    nextReminderAt = 10_000,
                ),
            ),
        )
        var failTaskSave = false
        var taskSaveCalls = 0
        var completeCalls = 0
        private var now = 100L

        override suspend fun snapshot() = AppSnapshot(projects = data.projects, tasks = data.tasks, captures = data.captures, notes = data.notes)
        override suspend fun preferences() = Preferences(autoRecord = false)
        override suspend fun savePreferences(value: Preferences) = Unit
        override suspend fun createProject(draft: ProjectDraft): Project = error("unused")
        override suspend fun updateProject(id: String, update: ProjectUpdate): Project = error("unused")
        override suspend fun pinProject(id: String, pinned: Boolean): Project = error("unused")
        override suspend fun orderPins(ids: List<String>) = Unit
        override suspend fun orderProjects(ids: List<String>) = Unit
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture = error("unused")
        override suspend fun distribute(id: String, request: DistributionRequest): Note = error("unused")
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = error("unused")
        override suspend fun updateNote(id: String, update: NoteUpdate): Note = error("unused")
        override suspend fun orderNotes(projectId: String, ids: List<String>) = Unit
        override suspend fun updateTask(id: String, update: TaskUpdate): Task {
            taskSaveCalls++
            check(!failTaskSave) { "save failed" }
            data = data.updateTask(id, update, now++)
            return data.tasks.first { it.id == id }
        }
        override suspend fun orderTasks(ids: List<String>) = Unit
        override suspend fun completeTask(id: String): Task {
            completeCalls++
            data = data.completeTask(id, now++)
            return data.tasks.first { it.id == id }
        }
        override suspend fun reprocess(id: String): Capture = error("unused")
        override suspend fun tidy(id: String): Capture = error("unused")
        override suspend fun rank(id: String): Capture = error("unused")
        override suspend fun discard(id: String) = Unit
        override suspend fun createDemo(): Capture = error("unused")
    }

    private object Recorder : RecorderGateway {
        override suspend fun hasConsent() = true
        override suspend fun hasPending() = false
        override fun phase() = "idle"
        override suspend fun start() = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun stopAndUpload(): Capture = error("unused")
        override suspend fun recoverPending(): Capture = error("unused")
    }

    private object Audio : AudioGateway {
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) = Unit
        override fun stop() = Unit
    }

    @Test
    fun failedWorkingCopySavePreventsCompletion() = runTest {
        val repo = Repo().apply { failTaskSave = true }
        val state = StudioState(repo, Recorder, Audio)
        state.launch()

        val completed = state.completeTaskFromDetail("t", "Новый текст")

        assertFalse(completed)
        assertEquals(1, repo.taskSaveCalls)
        assertEquals(0, repo.completeCalls)
        assertEquals("Старый текст", repo.data.tasks.single().text)
        assertFalse(repo.data.tasks.single().completed)
    }

    @Test
    fun successfulWorkingCopySaveHappensBeforeCompletion() = runTest {
        val repo = Repo()
        val state = StudioState(repo, Recorder, Audio)
        state.launch()

        val completed = state.completeTaskFromDetail("t", "Новый текст")

        assertTrue(completed)
        assertEquals(1, repo.taskSaveCalls)
        assertEquals(1, repo.completeCalls)
        assertEquals("Новый текст", repo.data.tasks.single().text)
        assertTrue(repo.data.tasks.single().completed)
        assertEquals(0, repo.data.tasks.single().nextReminderAt)
    }

    @Test
    fun unchangedWorkingCopyCompletesWithoutExtraSave() = runTest {
        val repo = Repo()
        val state = StudioState(repo, Recorder, Audio)
        state.launch()

        assertTrue(state.completeTaskFromDetail("t", "Старый текст"))
        assertEquals(0, repo.taskSaveCalls)
        assertEquals(1, repo.completeCalls)
    }
}
