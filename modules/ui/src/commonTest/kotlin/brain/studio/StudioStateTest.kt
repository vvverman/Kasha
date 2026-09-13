package brain.studio

import brain.domain.*
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudioStateTest {
    private class Repo : StudioRepository {
        override val simulated = true
        var prefs = Preferences(autoRecord = false)
        var data = BrainData(projects = listOf(Project("p", "Приложение")))
        var failSave = false
        var failDiscard = false
        var failCreate = false
        var snapshotGate: CompletableDeferred<Unit>? = null
        private var projectIndex = 1
        private var noteIndex = 1
        private var taskIndex = 1
        private var now = 10L

        override suspend fun snapshot(): AppSnapshot {
            snapshotGate?.await()
            return AppSnapshot(
                projects = data.projects,
                notes = data.notes,
                captures = data.captures,
                runtime = RuntimeStatus(simulated = true),
                tasks = data.tasks,
            )
        }
        override suspend fun preferences() = prefs
        override suspend fun savePreferences(value: Preferences) { prefs = value.validated() }
        override suspend fun createProject(draft: ProjectDraft): Project {
            check(!failCreate)
            val id = "p${++projectIndex}"
            data = data.addProject(id, now++, draft)
            return data.projects.last()
        }
        override suspend fun updateProject(id: String, update: ProjectUpdate): Project {
            data = data.updateProject(id, update, now++)
            return data.projects.first { it.id == id }
        }
        override suspend fun pinProject(id: String, pinned: Boolean): Project {
            data = data.pinProject(id, pinned)
            return data.projects.first { it.id == id }
        }
        override suspend fun orderPins(ids: List<String>) { data = data.orderPins(ids) }
        override suspend fun orderProjects(ids: List<String>) { data = data.orderProjects(ids) }
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
            check(!failSave)
            data = data.updateDraft(id, update)
            return data.captures.first { it.id == id }
        }
        override suspend fun distribute(id: String, request: DistributionRequest): Note {
            val (next, note) = data.distribute(id, request, "n${noteIndex++}", now++)
            data = next
            return note
        }
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
            val (next, task) = data.distributeTask(id, request, "t${taskIndex++}", now++)
            data = next
            return task
        }
        override suspend fun updateNote(id: String, update: NoteUpdate): Note {
            data = data.updateNote(id, update, now++)
            return data.notes.first { it.id == id }
        }
        override suspend fun pinNote(id: String, pinned: Boolean): Note {
            data = data.pinNote(id, pinned)
            return data.notes.first { it.id == id }
        }
        override suspend fun orderNotes(projectId: String, ids: List<String>) { data = data.orderNotes(projectId, ids) }
        override suspend fun updateTask(id: String, update: TaskUpdate): Task {
            data = data.updateTask(id, update, now++)
            return data.tasks.first { it.id == id }
        }
        override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
            data = data.rescheduleTask(id, update, now++)
            return data.tasks.first { it.id == id }
        }
        override suspend fun completeTask(id: String): Task {
            data = data.completeTask(id, now++)
            return data.tasks.first { it.id == id }
        }
        override suspend fun deleteTask(id: String) { data = data.deleteTask(id) }
        override suspend fun orderTasks(ids: List<String>) { data = data.orderTasks(ids) }
        override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
            val (next, due) = data.claimDueReminders(now, zoneId)
            data = next
            return due
        }
        override suspend fun reprocess(id: String) = data.captures.first { it.id == id }
        override suspend fun tidy(id: String): Capture {
            val c = data.captures.first { it.id == id }
            return updateCaptureDraft(id, CaptureDraftUpdate(text = DemoIntelligence(0).tidy(c.textToSave, "ru")))
        }
        override suspend fun rank(id: String) = data.captures.first { it.id == id }
        override suspend fun discard(id: String) {
            check(!failDiscard)
            data = data.copy(captures = data.captures.filterNot { it.id == id })
        }
        override suspend fun createDemo(): Capture {
            val id = "c${data.captures.size + 1}"
            val c = Capture(id, now++, title = "Тест", transcript = "Старый текст", status = CaptureStatus.QUEUED, simulated = true)
            data = data.addCapture(c)
            return c
        }
        fun ready() {
            data = data.copy(captures = data.captures.map {
                if (it.isInbox) it.copy(
                    status = CaptureStatus.READY,
                    preparedText = it.preparedText.ifBlank { it.transcript },
                    audioFinalized = true,
                    audioFileName = "audio/${it.id}/saved.m4a",
                ) else it
            })
        }
    }

    private class Recorder(private val repo: Repo) : RecorderGateway {
        var status = "idle"
        var starts = 0
        override suspend fun hasConsent() = true
        override suspend fun hasPending() = false
        override fun phase() = status
        override fun level() = if (status == "recording") 0.5f else 0f
        override suspend fun start() { starts++; status = "recording" }
        override suspend fun pause() { status = "paused" }
        override suspend fun resume() { status = "recording" }
        override suspend fun stopAndUpload(): Capture { status = "idle"; return repo.createDemo() }
        override suspend fun recoverPending(): Capture = repo.createDemo()
    }

    private class Audio : AudioGateway {
        var plays = 0
        var telemetry = AudioTelemetry()
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
            plays++
            telemetry = AudioTelemetry("playing", fromSeconds, 10.0)
        }
        override suspend fun pause() { telemetry = telemetry.copy(phase = "paused") }
        override suspend fun resume() { telemetry = telemetry.copy(phase = "playing") }
        override fun telemetry() = telemetry
        override fun stop() { telemetry = AudioTelemetry() }
    }

    @Test
    fun disabledAutostartDoesNotRequestMic() = runTest {
        val repo = Repo(); val recorder = Recorder(repo)
        StudioState(repo, recorder, Audio()).launch()
        assertEquals(0, recorder.starts)
    }

    @Test
    fun autostartRunsOnceAndOnlyWithoutCurrentCapture() = runTest {
        val repo = Repo().apply { prefs = Preferences(autoRecord = true) }
        val recorder = Recorder(repo); val state = StudioState(repo, recorder, Audio())
        state.launch(); state.launch()
        assertEquals(1, recorder.starts)
    }

    @Test
    fun navigationDoesNotStopRecording() = runTest {
        val repo = Repo(); val recorder = Recorder(repo); val state = StudioState(repo, recorder, Audio())
        state.launch(); state.startRecording(); state.navigate(Tab.PROJECTS); state.navigate(Tab.SETTINGS)
        assertEquals("recording", recorder.status)
    }

    @Test
    fun noteDraftHasOnlyTextAndTitleFollowsFirstLine() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Первая строка\nОстальной текст")
        state.flush(); state.refresh()
        assertEquals("Первая строка", state.title)
        assertEquals("Первая строка", repo.data.captures.single().title)
        assertEquals("Первая строка\nОстальной текст", repo.data.captures.single().textToSave)
    }

    @Test
    fun failedTextSaveCannotOpenNoteDestination() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Изменено"); repo.failSave = true
        state.sendToNotes()
        assertFalse(state.choosingProject)
        assertTrue(repo.data.notes.isEmpty())
    }

    @Test
    fun sendToNotesOpensOnlyProjectPickerAndSavesNote() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Название из текста\nТело")
        state.sendToNotes()
        assertTrue(state.choosingProject)
        assertNull(state.taskScheduleTarget)
        state.distribute("p")
        assertNull(state.current)
        assertFalse(state.choosingProject)
        assertEquals("Название из текста", repo.data.notes.single().title)
        assertEquals("Название из текста\nТело", repo.data.notes.single().body)
    }

    @Test
    fun savedNoteEditRecomputesTitleFromFirstLine() = runTest {
        val repo = Repo().apply {
            data = BrainData(
                projects = listOf(Project("p", "Приложение")),
                notes = listOf(Note("n", "p", "Старое", "Старое\nТело", 1, 1)),
            )
        }
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch(); state.openNote("n"); state.beginNoteEdit("n")
        state.saveNote("n", "Новое название\nИсправленный текст")
        assertNull(state.editingNoteId)
        assertEquals("Новое название", state.snapshot.notes.single().title)
        assertEquals("Новое название\nИсправленный текст", state.snapshot.notes.single().body)
    }

    @Test
    fun sendToTasksOpensScheduleWithoutProjectPicker() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Сделать задачу\nПодробности")
        state.sendToTasks()
        assertEquals("new", state.taskScheduleTarget)
        assertFalse(state.choosingProject)
        state.saveTaskSchedule(10_000, ReminderRepeat.THIRTY_MINUTES)
        assertNull(state.current)
        assertNull(state.taskScheduleTarget)
        assertEquals(Tab.TASKS, state.tab)
        val task = state.snapshot.tasks.single()
        assertNull(task.projectId)
        assertEquals("Сделать задачу\nПодробности", task.text)
        assertEquals(10_000, task.dueAt)
        assertEquals(ReminderRepeat.THIRTY_MINUTES, task.reminderRepeat)
    }

    @Test
    fun taskCanBeEditedRescheduledCompletedArchivedAndDeleted() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Задача")
        state.sendToTasks(); state.saveTaskSchedule(10_000, ReminderRepeat.HOURLY)
        val id = state.snapshot.tasks.single().id

        state.saveTask(id, "Задача\nНовое тело")
        state.editTaskSchedule(id)
        state.saveTaskSchedule(20_000, ReminderRepeat.DAILY)
        var task = state.snapshot.tasks.single()
        assertEquals("Задача\nНовое тело", task.text)
        assertEquals(20_000, task.dueAt)
        assertEquals(ReminderRepeat.DAILY, task.reminderRepeat)

        state.completeTask(id)
        assertTrue(state.snapshot.tasks.single().completed)
        assertTrue(state.tasks().isEmpty())
        state.taskArchive = true
        assertEquals(id, state.tasks().single().id)

        state.deleteTask(id)
        assertTrue(state.snapshot.tasks.isEmpty())
    }

    @Test
    fun manualTaskOrderAppliesOnlyToActiveTasks() = runTest {
        val repo = Repo().apply {
            prefs = Preferences(autoRecord = false, taskSort = SortMode.MANUAL)
            data = data.copy(tasks = listOf(
                Task("a", text = "A", createdAt = 1, updatedAt = 1, manualOrder = 0),
                Task("b", text = "B", createdAt = 2, updatedAt = 2, manualOrder = 1, completedAt = 3),
                Task("c", text = "C", createdAt = 3, updatedAt = 3, manualOrder = 2),
            ))
        }
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.reorderTasks(listOf("c", "a"))
        assertEquals(listOf("c", "a"), state.tasks().map { it.id })
        state.taskArchive = true
        assertEquals(listOf("b"), state.tasks().map { it.id })
    }

    @Test
    fun emptyInstallationGetsLocalizedStarterProjectOnce() = runTest {
        val repo = Repo().apply { data = BrainData(); prefs = Preferences(autoRecord = false, language = "de") }
        val first = StudioState(repo, Recorder(repo), Audio(), "ru-RU")
        first.launch()
        assertEquals("Dein erstes Projekt", repo.data.projects.single().title)
        val before = repo.data.projects
        val second = StudioState(repo, Recorder(repo), Audio(), "en-US")
        second.launch()
        assertEquals(before, second.snapshot.projects)
    }

    @Test
    fun projectCreationFromNotePickerKeepsCurrentCapture() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready()
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch()
        state.editText("Мой текст"); state.sendToNotes()
        val captureId = state.current!!.id
        state.beginProjectCreation(fromPicker = true)
        state.createProject("Новый проект", "")
        assertTrue(state.choosingProject)
        assertNotNull(state.targetProjectId)
        assertEquals(captureId, state.current!!.id)
        assertEquals("Мой текст", state.text)
    }

    @Test
    fun playbackAndRecordingStayMutuallyExclusive() = runTest {
        val repo = Repo(); val recorder = Recorder(repo); val audio = Audio(); val state = StudioState(repo, recorder, audio)
        state.launch(); state.requestListen("saved")
        state.startRecording()
        assertEquals(0, recorder.starts)
        assertEquals("stopPlayback", state.error)
    }

    @Test
    fun failedDiscardKeepsCurrentCapture() = runTest {
        val repo = Repo(); repo.createDemo(); repo.ready(); repo.failDiscard = true
        val state = StudioState(repo, Recorder(repo), Audio()); state.launch(); state.discard()
        assertNotNull(state.current)
    }

    @Test
    fun localizationIsCompleteAndExplicit() {
        assertTrue(Copy.keys().size > 60)
        for (key in Copy.keys()) for (lang in Languages.codes) assertTrue(Copy.text(lang, key).isNotBlank())
    }
}
