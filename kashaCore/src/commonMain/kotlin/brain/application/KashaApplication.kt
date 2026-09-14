package brain.application

import brain.domain.NoteText
import brain.domain.ProjectOrder
import brain.domain.SnapshotQueries
import brain.domain.UserSort
import brain.model.*
import brain.studio.Preferences
import brain.studio.StudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Состояние редактирования текущего capture, не отдельный раздел или сущность «Черновики». */
data class CaptureEditState(
    val captureId: String? = null,
    val text: String = "",
    val revision: Long = 0,
    val dirty: Boolean = false,
)

/** Только данные приложения: без экранов, Compose, системных handle и секретов. */
data class KashaApplicationState(
    val snapshot: AppSnapshot = AppSnapshot(),
    val preferences: Preferences = Preferences(),
    val edit: CaptureEditState = CaptureEditState(),
    val publishingCaptureId: String? = null,
) {
    val current: Capture? get() = snapshot.captures.firstOrNull { it.isInbox }
    val title: String get() = NoteText.title(edit.text)
    val canEdit: Boolean get() = current != null && publishingCaptureId == null

    fun projects(): List<Project> = UserSort.projects(snapshot.projects, preferences.projectSort)
    fun projectNotes(id: String): List<Note> =
        UserSort.notes(snapshot.notes.filter { it.projectId == id }, preferences.noteSort)
    fun tasks(archive: Boolean): List<Task> = SnapshotQueries.tasks(snapshot.tasks, preferences.taskSort, archive)
    fun destinationProjects(): List<Project> = ProjectOrder.sorted(snapshot.projects, current?.relevance.orEmpty())
    fun noteSources(id: String): List<Capture> = snapshot.captures.filter { it.noteId == id }.sortedBy { it.appendedAt }
}

/**
 * Общий API приложения. Первый перенесённый срез — данные, редактор и команды контента.
 * Запись/плеер пока подключаются прежним общим StudioState и переносятся следующим срезом.
 *
 * Команды сериализованы; редактирование во время autosave/AI не теряет новую версию текста.
 * UI наблюдает state и отправляет команды, но не изменяет snapshot напрямую.
 * Правила данных и идемпотентность остаются в существующих BrainData/StudioRepository.
 * Ошибки и отмена корутин передаются вызывающему; перевод и навигация принадлежат UI.
 */
class KashaApplication(private val repository: StudioRepository) {
    private val mutableState = MutableStateFlow(KashaApplicationState())
    val state: StateFlow<KashaApplicationState> = mutableState.asStateFlow()
    private val commands = Mutex()

    suspend fun loadPreferences() = commands.withLock {
        val preferences = repository.preferences().validated()
        mutableState.update { it.copy(preferences = preferences) }
    }

    suspend fun refresh() = commands.withLock { refreshUnlocked() }

    /** false означает, что capture отсутствует или уже фиксируется его назначение. */
    fun editText(value: String): Boolean {
        while (true) {
            val before = mutableState.value
            if (!before.canEdit) return false
            val next = before.copy(edit = before.edit.copy(text = value, revision = before.edit.revision + 1, dirty = true))
            if (mutableState.compareAndSet(before, next)) return true
        }
    }

    suspend fun flush() = commands.withLock { flushUnlocked() }

    suspend fun savePreferences(value: Preferences) = updatePreferences { value }

    /** Преобразование применяется к последним подтверждённым настройкам внутри одной команды. */
    suspend fun updatePreferences(change: (Preferences) -> Preferences) = commands.withLock {
        val next = change(mutableState.value.preferences).validated()
        repository.savePreferences(next)
        mutableState.update { it.copy(preferences = next) }
    }

    suspend fun setProjectSort(mode: SortMode) = updatePreferences { it.copy(projectSort = mode) }
    suspend fun setNoteSort(mode: SortMode) = updatePreferences { it.copy(noteSort = mode) }
    suspend fun setTaskSort(mode: SortMode) = updatePreferences { it.copy(taskSort = mode) }

    suspend fun reorderProjects(ids: List<String>) = mutate {
        check(mutableState.value.preferences.projectSort == SortMode.MANUAL)
        repository.orderProjects(ids)
    }

    suspend fun reorderNotes(projectId: String, ids: List<String>) = mutate {
        check(mutableState.value.preferences.noteSort == SortMode.MANUAL)
        repository.orderNotes(projectId, ids)
    }

    suspend fun reorderTasks(ids: List<String>) = mutate {
        check(mutableState.value.preferences.taskSort == SortMode.MANUAL)
        repository.orderTasks(ids)
    }

    suspend fun createProject(draft: ProjectDraft): Project = mutate { repository.createProject(draft) }

    suspend fun updateProject(id: String, title: String, instruction: String): Project = mutate {
        val project = mutableState.value.snapshot.projects.first { it.id == id }
        repository.updateProject(id, ProjectUpdate(title, project.description, instruction))
    }

    suspend fun pinProject(id: String, pinned: Boolean): Project = mutate { repository.pinProject(id, pinned) }
    suspend fun pinNote(id: String, pinned: Boolean): Note = mutate { repository.pinNote(id, pinned) }
    suspend fun orderNotePins(projectId: String, ids: List<String>) = mutate { repository.orderNotePins(projectId, ids) }

    suspend fun moveProjectPin(id: String, delta: Int): Boolean = commands.withLock {
        val ids = ProjectOrder.sorted(mutableState.value.snapshot.projects).filter { it.pinned }.map { it.id }.toMutableList()
        val old = ids.indexOf(id)
        val next = old.toLong() + delta.toLong()
        if (old < 0 || delta == 0 || next < 0 || next >= ids.size) return@withLock false
        ids.removeAt(old)
        ids.add(next.toInt(), id)
        repository.orderPins(ids)
        refreshUnlocked()
        true
    }

    suspend fun moveNotePin(id: String, delta: Int): Boolean = commands.withLock {
        val note = mutableState.value.snapshot.notes.firstOrNull { it.id == id } ?: return@withLock false
        if (!note.pinned || delta == 0) return@withLock false
        val ids = mutableState.value.snapshot.notes
            .filter { it.projectId == note.projectId && it.pinned }
            .sortedWith(compareBy<Note> { it.pinOrder }.thenBy { it.createdAt }.thenBy { it.id })
            .map { it.id }.toMutableList()
        val old = ids.indexOf(id)
        val next = old.toLong() + delta.toLong()
        if (old < 0 || next < 0 || next >= ids.size) return@withLock false
        ids.removeAt(old)
        ids.add(next.toInt(), id)
        repository.orderNotePins(note.projectId, ids)
        refreshUnlocked()
        true
    }

    suspend fun saveNote(id: String, body: String): Note = mutate { repository.updateNote(id, NoteUpdate(body = body)) }
    suspend fun saveTask(id: String, text: String): Task = mutate { repository.updateTask(id, TaskUpdate(text)) }
    suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task = mutate { repository.rescheduleTask(id, update) }
    suspend fun completeTask(id: String): Task = mutate { repository.completeTask(id) }
    suspend fun deleteTask(id: String) = mutate { repository.deleteTask(id) }

    suspend fun retry(captureId: String): Capture = mutate {
        requireCurrent(captureId)
        repository.reprocess(captureId)
    }

    suspend fun tidy(captureId: String): Capture = mutate {
        requireCurrent(captureId)
        flushUnlocked()
        repository.tidy(captureId)
    }

    /** Готовит данные выбора назначения; открывать экран должен общий UI. */
    suspend fun prepareNotes(captureId: String): Capture = mutate {
        requireCurrent(captureId)
        flushUnlocked()
        check(requireCurrent(captureId).textToSave.isNotBlank()) { "emptyText" }
        repository.rank(captureId)
    }

    suspend fun prepareTask(captureId: String) = commands.withLock {
        requireCurrent(captureId)
        flushUnlocked()
        check(requireCurrent(captureId).textToSave.isNotBlank()) { "emptyText" }
    }

    suspend fun distribute(captureId: String, request: DistributionRequest): Note = publish(captureId) {
        repository.distribute(captureId, request)
    }

    suspend fun distributeTask(captureId: String, request: TaskDistributionRequest): Task = publish(captureId) {
        repository.distributeTask(captureId, request)
    }

    /** Вызывающий транспорт освобождает проигрываемый источник до удаления. */
    suspend fun discard(captureId: String) = commands.withLock {
        requireCurrent(captureId)
        mutableState.update { it.copy(publishingCaptureId = captureId) }
        try {
            repository.discard(captureId)
            refreshUnlocked()
        } finally {
            mutableState.update { it.copy(publishingCaptureId = null) }
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = commands.withLock {
        val result = block()
        refreshUnlocked()
        result
    }

    private suspend fun <T> publish(captureId: String, block: suspend () -> T): T = commands.withLock {
        require(captureId.isNotBlank())
        val capture = mutableState.value.snapshot.captures.firstOrNull { it.id == captureId }
            ?: error("currentExists")
        // Повтор для уже опубликованного capture передаётся существующему идемпотентному контракту.
        if (capture.isInbox) requireCurrent(captureId)
        mutableState.update { it.copy(publishingCaptureId = captureId) }
        try {
            if (capture.isInbox) flushUnlocked()
            val result = block()
            refreshUnlocked()
            result
        } finally {
            mutableState.update { it.copy(publishingCaptureId = null) }
        }
    }

    private fun requireCurrent(id: String): Capture = mutableState.value.current
        ?.takeIf { it.id == id } ?: error("currentExists")

    private suspend fun flushUnlocked() {
        val before = mutableState.value
        val capture = before.current ?: return
        val edit = before.edit
        if (!edit.dirty || capture.status.isWorking) return
        check(edit.captureId == capture.id) { "currentExists" }
        val saved = repository.updateCaptureDraft(capture.id, CaptureDraftUpdate(text = edit.text))
        check(saved.id == capture.id) { "saveFailed" }
        mutableState.update { latest ->
            latest.copy(
                snapshot = latest.snapshot.copy(captures = latest.snapshot.captures.map { if (it.id == saved.id) saved else it }),
                edit = if (latest.edit.captureId == capture.id && latest.edit.revision == edit.revision)
                    latest.edit.copy(dirty = false) else latest.edit,
            )
        }
    }

    private suspend fun refreshUnlocked() {
        val snapshot = repository.snapshot()
        mutableState.update { before ->
            val current = snapshot.captures.firstOrNull { it.isInbox }
            val edit = before.edit
            before.copy(
                snapshot = snapshot,
                edit = if (edit.captureId == current?.id && edit.dirty) edit else
                    CaptureEditState(current?.id, current?.textToSave.orEmpty(), edit.revision, dirty = false),
            )
        }
    }
}
