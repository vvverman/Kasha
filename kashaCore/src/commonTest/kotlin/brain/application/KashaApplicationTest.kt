package brain.application

import brain.domain.*
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KashaApplicationTest {
    /** Настоящие Core-правила с управляемыми задержками и ошибками адаптера хранения. */
    private class Repo : StudioRepository {
        override val simulated = true
        var prefs = Preferences(autoRecord = false)
        var data = BrainData(
            projects = listOf(Project("p", "Проект", description = "Описание")),
            captures = listOf(Capture("c", 1, transcript = "Исходный текст", status = CaptureStatus.READY, audioFinalized = true)),
        )
        var failSave = false
        var failPreferences = false
        var failDiscard = false
        var saveGate: CompletableDeferred<Unit>? = null
        var publishGate: CompletableDeferred<Unit>? = null
        var tidyGate: CompletableDeferred<Unit>? = null
        var snapshotGate: CompletableDeferred<Unit>? = null
        var preferencesGate: CompletableDeferred<Unit>? = null
        var publications = 0
        private var nextId = 0
        private var now = 10L

        override suspend fun snapshot(): AppSnapshot {
            val result = AppSnapshot(projects = data.projects, notes = data.notes, captures = data.captures, tasks = data.tasks)
            snapshotGate?.await()
            return result
        }
        override suspend fun preferences() = prefs
        override suspend fun savePreferences(value: Preferences) {
            preferencesGate?.await()
            check(!failPreferences)
            prefs = value.validated()
        }
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
            saveGate?.await()
            check(!failSave)
            data = data.updateDraft(id, update)
            return data.captures.first { it.id == id }
        }
        override suspend fun distribute(id: String, request: DistributionRequest): Note {
            publishGate?.await()
            publications++
            val (next, note) = data.distribute(id, request, "n${++nextId}", now++)
            data = next
            return note
        }
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
            publishGate?.await()
            publications++
            val (next, task) = data.distributeTask(id, request, "t${++nextId}", now++)
            data = next
            return task
        }
        override suspend fun createProject(draft: ProjectDraft): Project {
            val id = "p${++nextId}"
            data = data.addProject(id, now++, draft)
            return data.projects.first { it.id == id }
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
        override suspend fun updateNote(id: String, update: NoteUpdate): Note {
            data = data.updateNote(id, update, now++)
            return data.notes.first { it.id == id }
        }
        override suspend fun pinNote(id: String, pinned: Boolean): Note {
            data = data.pinNote(id, pinned)
            return data.notes.first { it.id == id }
        }
        override suspend fun orderNotePins(projectId: String, ids: List<String>) { data = data.orderNotePins(projectId, ids) }
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
        override suspend fun reprocess(id: String) = data.captures.first { it.id == id }
        override suspend fun tidy(id: String): Capture {
            tidyGate?.await()
            return updateCaptureDraft(id, CaptureDraftUpdate(text = "Обработанный текст"))
        }
        override suspend fun rank(id: String) = data.captures.first { it.id == id }
        override suspend fun discard(id: String) {
            check(!failDiscard)
            data = data.copy(captures = data.captures.filterNot { it.id == id })
        }
        override suspend fun createDemo(): Capture = error("Этот тест не использует демонстрационную запись")
    }

    private suspend fun application(repo: Repo = Repo()): KashaApplication = KashaApplication(repo).also {
        it.loadPreferences()
        it.refresh()
    }

    @Test fun titleAndTextAreManagedWithoutCompose() = runTest {
        val repo = Repo(); val app = application(repo)
        assertTrue(app.editText("\nЗаголовок\nТекст 123"))
        app.flush()
        assertEquals("Заголовок", app.state.value.title)
        assertEquals("\nЗаголовок\nТекст 123", repo.data.captures.single().textToSave)
        assertFalse(app.state.value.edit.dirty)
    }

    @Test fun failedSaveKeepsUnsavedText() = runTest {
        val repo = Repo(); val app = application(repo)
        app.editText("Не терять")
        repo.failSave = true
        assertFailsWith<IllegalStateException> { app.flush() }
        assertEquals("Не терять", app.state.value.edit.text)
        assertTrue(app.state.value.edit.dirty)
        assertEquals("Исходный текст", repo.data.captures.single().textToSave)
    }

    @Test fun newerEditSurvivesSuspendedAutosaveAndRefresh() = runTest {
        val repo = Repo(); val app = application(repo)
        app.editText("Первая редакция")
        repo.saveGate = CompletableDeferred()
        val saving = async { app.flush() }
        runCurrent()
        app.editText("Новая редакция")
        repo.saveGate!!.complete(Unit)
        saving.await(); app.refresh()
        assertEquals("Новая редакция", app.state.value.edit.text)
        assertTrue(app.state.value.edit.dirty)
        app.flush()
        assertEquals("Новая редакция", repo.data.captures.single().textToSave)
    }

    @Test fun delayedSnapshotDoesNotOverwriteTyping() = runTest {
        val repo = Repo(); val app = application(repo)
        repo.snapshotGate = CompletableDeferred()
        val refreshing = async { app.refresh() }
        runCurrent()
        app.editText("Напечатано во время чтения")
        repo.snapshotGate!!.complete(Unit)
        refreshing.await()
        assertEquals("Напечатано во время чтения", app.state.value.edit.text)
        assertTrue(app.state.value.edit.dirty)
    }

    @Test fun lateTidyDoesNotOverwriteNewerLocalEdit() = runTest {
        val repo = Repo(); val app = application(repo)
        repo.tidyGate = CompletableDeferred()
        val tidying = async { app.tidy("c") }
        runCurrent()
        app.editText("Моя новая мысль")
        repo.tidyGate!!.complete(Unit)
        tidying.await()
        assertEquals("Моя новая мысль", app.state.value.edit.text)
        assertTrue(app.state.value.edit.dirty)
        app.flush()
        assertEquals("Моя новая мысль", repo.data.captures.single().textToSave)
    }

    @Test fun failedFlushDoesNotPublishAndReopensEditing() = runTest {
        val repo = Repo(); val app = application(repo)
        app.editText("Не сохранено"); repo.failSave = true
        assertFailsWith<IllegalStateException> { app.distribute("c", DistributionRequest("p")) }
        assertEquals(0, repo.publications)
        assertTrue(repo.data.notes.isEmpty())
        assertNull(app.state.value.publishingCaptureId)
        assertTrue(app.state.value.canEdit)
        assertEquals("Не сохранено", app.state.value.edit.text)
    }

    @Test fun publicationFreezesTheSubmittedRevision() = runTest {
        val repo = Repo(); val app = application(repo)
        app.editText("Сохраняем именно это")
        repo.publishGate = CompletableDeferred()
        val publishing = async { app.distribute("c", DistributionRequest("p")) }
        runCurrent()
        assertEquals("c", app.state.value.publishingCaptureId)
        assertFalse(app.editText("Слишком поздняя правка"))
        repo.publishGate!!.complete(Unit)
        val note = publishing.await()
        assertEquals("Сохраняем именно это", note.body)
        assertNull(app.state.value.current)
        assertNull(app.state.value.publishingCaptureId)
    }

    @Test fun cancelledPublicationReleasesCommandAndKeepsCapture() = runTest {
        val repo = Repo(); val app = application(repo)
        repo.publishGate = CompletableDeferred()
        val publishing = launch { app.distribute("c", DistributionRequest("p")) }
        runCurrent(); publishing.cancelAndJoin()
        assertNull(app.state.value.publishingCaptureId)
        assertTrue(app.state.value.canEdit)
        assertTrue(repo.data.notes.isEmpty())
        repo.publishGate = null
        app.distribute("c", DistributionRequest("p"))
        assertEquals(1, repo.data.notes.size)
    }

    @Test fun concurrentPublicationUsesExistingIdempotentDomainContract() = runTest {
        val repo = Repo(); val app = application(repo)
        val notes = awaitAll(
            async { app.distribute("c", DistributionRequest("p")) },
            async { app.distribute("c", DistributionRequest("p")) },
        )
        assertEquals(notes[0].id, notes[1].id)
        assertEquals(1, repo.data.notes.size)
        assertEquals(1, app.state.value.noteSources(notes[0].id).size)
    }

    @Test fun appendPreservesPrefixAndIsNotRepeated() = runTest {
        val repo = Repo()
        val prefix = "Заголовок\nСтарый текст с пробелами   "
        repo.data = repo.data.copy(notes = listOf(Note("n", "p", "Заголовок", prefix, 1, 1)))
        val app = application(repo)
        app.editText("Добавление")
        val first = app.distribute("c", DistributionRequest("p", "n"))
        val repeated = app.distribute("c", DistributionRequest("p", "n"))
        assertEquals(prefix + "\n\nДобавление", first.body)
        assertEquals(first, repeated)
        assertEquals(1, repo.data.notes.size)
    }

    @Test fun noteCannotAlsoBecomeTask() = runTest {
        val repo = Repo(); val app = application(repo)
        app.distribute("c", DistributionRequest("p"))
        assertFailsWith<IllegalArgumentException> {
            app.distributeTask("c", TaskDistributionRequest(dueAt = 100_000, reminderRepeat = ReminderRepeat.HOURLY))
        }
        assertTrue(repo.data.tasks.isEmpty())
        assertEquals(1, repo.data.notes.size)
    }

    @Test fun oldReceiptDoesNotFlushAnotherCapture() = runTest {
        val repo = Repo(); val app = application(repo)
        val first = app.distribute("c", DistributionRequest("p"))
        repo.data = repo.data.addCapture(Capture("c2", 2, transcript = "Вторая", status = CaptureStatus.READY, audioFinalized = true))
        app.refresh(); app.editText("Несохранённая вторая")
        assertEquals(first.id, app.distribute("c", DistributionRequest("p")).id)
        assertEquals("c2", app.state.value.current?.id)
        assertEquals("Несохранённая вторая", app.state.value.edit.text)
        assertTrue(app.state.value.edit.dirty)
        assertEquals("Вторая", repo.data.captures.first { it.id == "c2" }.textToSave)
    }

    @Test fun taskLifecycleUsesCoreWithoutProjectOrUi() = runTest {
        val repo = Repo().apply { data = data.copy(projects = emptyList()) }
        val app = application(repo)
        app.prepareTask("c")
        val task = app.distributeTask("c", TaskDistributionRequest(dueAt = 100_000, reminderRepeat = ReminderRepeat.HOURLY))
        app.saveTask(task.id, "Новый текст")
        app.rescheduleTask(task.id, TaskScheduleUpdate(200_000, ReminderRepeat.DAILY))
        assertEquals(200_000L, app.state.value.tasks(false).single().dueAt)
        app.completeTask(task.id)
        assertTrue(app.state.value.tasks(false).isEmpty())
        assertEquals("Новый текст", app.state.value.tasks(true).single().text)
        app.deleteTask(task.id)
        assertTrue(app.state.value.tasks(true).isEmpty())
    }

    @Test fun preferenceChangesUseLatestConfirmedValues() = runTest {
        val repo = Repo(); val app = application(repo)
        repo.preferencesGate = CompletableDeferred()
        val theme = async { app.updatePreferences { it.copy(theme = "dark") } }
        runCurrent()
        val language = async { app.updatePreferences { it.copy(language = "de") } }
        runCurrent()
        repo.preferencesGate!!.complete(Unit)
        awaitAll(theme, language)
        assertEquals("dark", app.state.value.preferences.theme)
        assertEquals("de", app.state.value.preferences.language)
        assertEquals(repo.prefs, app.state.value.preferences)
    }

    @Test fun failedPreferencesDoNotClaimSavedValue() = runTest {
        val repo = Repo(); val app = application(repo)
        repo.failPreferences = true
        assertFailsWith<IllegalStateException> { app.updatePreferences { it.copy(theme = "dark") } }
        assertEquals("system", app.state.value.preferences.theme)
        assertEquals(repo.prefs, app.state.value.preferences)
    }

    @Test fun orderingAndPinsRemainIndependentOfSelectedSort() = runTest {
        val repo = Repo(); val app = application(repo)
        val second = app.createProject(ProjectDraft("Другой"))
        assertFailsWith<IllegalStateException> { app.reorderProjects(listOf(second.id, "p")) }
        app.setProjectSort(SortMode.MANUAL)
        app.reorderProjects(listOf(second.id, "p"))
        app.pinProject("p", true)
        assertEquals(listOf("p", second.id), app.state.value.projects().map { it.id })
        app.setProjectSort(SortMode.ALPHABETICAL)
        app.setProjectSort(SortMode.MANUAL)
        app.pinProject("p", false)
        assertEquals(listOf(second.id, "p"), app.state.value.projects().map { it.id })
    }

    @Test fun projectEditingPreservesDescription() = runTest {
        val app = application()
        app.updateProject("p", "Новое имя", "Инструкция")
        assertEquals("Описание", app.state.value.snapshot.projects.single().description)
        assertEquals("Инструкция", app.state.value.snapshot.projects.single().instruction)
    }

    @Test fun movingNotePinDoesNotAffectOtherProjectOrManualOrder() = runTest {
        val repo = Repo()
        repo.data = repo.data.copy(
            projects = repo.data.projects + Project("other", "Другой"),
            notes = listOf(
                Note("n1", "p", "Первая", "Первая", 1, 1, pinned = true, pinOrder = 0, manualOrder = 7),
                Note("n2", "p", "Вторая", "Вторая", 2, 2, pinned = true, pinOrder = 1, manualOrder = 8),
                Note("n3", "other", "Третья", "Третья", 3, 3, pinned = true, pinOrder = 0),
            ),
        )
        val app = application(repo)
        val other = repo.data.notes.last()
        assertTrue(app.moveNotePin("n2", -1))
        assertEquals(listOf("n2", "n1"), app.state.value.projectNotes("p").map { it.id })
        assertEquals(listOf(7, 8), repo.data.notes.take(2).map { it.manualOrder })
        assertEquals(other, repo.data.notes.last())
        assertFalse(app.moveNotePin("n2", -1))
    }

    @Test fun failedDiscardKeepsCaptureAndEditingAvailable() = runTest {
        val repo = Repo(); val app = application(repo)
        app.editText("Оставить"); repo.failDiscard = true
        assertFailsWith<IllegalStateException> { app.discard("c") }
        assertEquals("Оставить", app.state.value.edit.text)
        assertTrue(app.state.value.canEdit)
        assertNull(app.state.value.publishingCaptureId)
    }

    @Test fun stateCanBeObservedWithoutComposeAndReplaysLatestValue() = runTest {
        val app = application()
        val observed = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            app.state.collect { observed += it.edit.text }
        }
        app.editText("Обновление")
        assertEquals(listOf("Исходный текст", "Обновление"), observed)
        assertEquals("Обновление", app.state.value.edit.text)
    }

    @Test fun noCaptureCannotBeEditedOrPublished() = runTest {
        val repo = Repo().apply { data = data.copy(captures = emptyList()) }
        val app = application(repo)
        assertFalse(app.editText("Нет источника"))
        assertFailsWith<IllegalStateException> { app.distribute("c", DistributionRequest("p")) }
        assertEquals(0, repo.publications)
    }
}
