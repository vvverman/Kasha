package brain.studio

import androidx.compose.runtime.*
import brain.application.KashaApplication
import brain.application.PlaybackRequestResult
import brain.application.ApplicationPollFailure
import brain.domain.*
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

enum class Tab { HOME, PROJECTS, TASKS, SETTINGS }

class StudioState(
    val repository: StudioRepository,
    val recorder: RecorderGateway,
    val audio: AudioGateway,
    val systemLanguage: String = "ru",
    val reminders: ReminderGateway = NoopReminderGateway,
) {
    val application = KashaApplication(repository, recorder, audio, reminders)
    // Ссылка на неизменяемое состояние Core для Compose; UI не правит его поля.
    private var content by mutableStateOf(application.state.value)
    val snapshot get() = content.snapshot
    val preferences get() = content.preferences
    val language: String get() = Languages.resolve(preferences.language, systemLanguage)
    val current: Capture? get() = content.current
    val title: String get() = content.title
    val text: String get() = content.edit.text
    val editRevision: Long get() = content.edit.revision

    var tab by mutableStateOf(Tab.HOME); private set
    var selectedProjectId by mutableStateOf<String?>(null)
    var selectedNoteId by mutableStateOf<String?>(null)
    var selectedTaskId by mutableStateOf<String?>(null)
    var editingProjectId by mutableStateOf<String?>(null)
    var editingNoteId by mutableStateOf<String?>(null)
    var choosingProject by mutableStateOf(false)
    var targetProjectId by mutableStateOf<String?>(null)
    /** "new" — задача из текущей записи, иначе id существующей задачи. */
    var taskScheduleTarget by mutableStateOf<String?>(null)
    var taskArchive by mutableStateOf(false)
    var languagePage by mutableStateOf(false)

    var busy by mutableStateOf(false); private set
    val controlBusy get() = content.transport.operation != null
    var error by mutableStateOf<String?>(null)
    internal var deleteConfirmation by mutableStateOf<DeleteConfirmation?>(null); private set
    var confirmListenId by mutableStateOf<String?>(null)
    val activeRecordingSessionId get() = content.transport.activeSessionId
    val recordPhase get() = content.transport.recorderPhase.legacyValue
    val recorderIssue get() = content.transport.recorderIssue
    val elapsed get() = content.transport.elapsedMillis / 1000
    val liveWave get() = content.transport.liveWave
    val playback get() = content.transport.telemetry
    val playbackRate get() = content.transport.playbackRate
    val loadedAudioId get() = content.transport.loadedAudioId
    val pending get() = content.transport.hasPending
    val initialized get() = content.initialized
    val reminderDeliveryFailed get() = content.reminderDeliveryFailed

    private var autoRouteFor: String? = null
    private var creatingForCaptureId: String? = null
    private var actionScope: CoroutineScope? = null
    fun attachActionScope(scope: CoroutineScope) { actionScope = scope }
    fun detachActionScope(scope: CoroutineScope) { if (actionScope === scope) actionScope = null }

    private fun syncContent() {
        val previous = current
        content = application.state.value
        reconcileContentPresentation(previous)
    }

    private suspend fun <T> core(block: suspend KashaApplication.() -> T): T =
        try { application.block() } finally { syncContent() }

    val recording get() = content.transport.recording
    val recorderCanResume get() = content.transport.recorderCanResume
    val working get() = current?.status?.isWorking == true
    val loadedAudio get() = snapshot.captures.firstOrNull { it.id == loadedAudioId }
    fun tr(key: String) = Copy.text(language, key)

    fun editText(value: String) {
        application.editText(value)
        syncContent()
    }

    fun navigate(value: Tab) {
        tab = value
        choosingProject = false
        targetProjectId = null
        taskScheduleTarget = null
        editingProjectId = null
        editingNoteId = null
        creatingForCaptureId = null
        selectedProjectId = null
        selectedNoteId = null
        selectedTaskId = null
        languagePage = false
    }

    fun beginProjectCreation(fromPicker: Boolean = false) {
        creatingForCaptureId = if (fromPicker && choosingProject) current?.id else null
        editingProjectId = "new"
    }

    fun cancelProjectEdit() {
        editingProjectId = null
        creatingForCaptureId = null
    }

    suspend fun launch() {
        action { core { launch(systemLanguage) { language -> Copy.text(language, "firstProject") } } }
    }

    suspend fun refresh() {
        val previous = current
        core { refresh() }
        reconcileContentPresentation(previous)
    }

    private fun reconcileContentPresentation(previous: Capture?) {
        val c = current
        if (c != null && previous?.status?.isWorking == true && !c.status.isWorking && c.status == CaptureStatus.READY && autoRouteFor != c.id) {
            autoRouteFor = c.id
            if (preferences.autoRoute) {
                choosingProject = true
                targetProjectId = null
            }
        }
    }

    suspend fun flush() = core { flush() }

    suspend fun autosave() {
        try { flush() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "saveFailed" }
    }

    suspend fun poll() = coroutineScope {
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            application.state.collect { syncContent() }
        }
        try {
            application.poll(::handlePollFailure)
        } finally { observer.cancel() }
    }

    internal fun handlePollFailure(failure: ApplicationPollFailure) {
        pollFailureMessageKey(failure)?.let { error = it }
    }

    suspend fun savePreferences(value: Preferences) = updatePreferences { value }

    /** Изменения полей ставятся в очередь Core, а не теряются из-за занятого экрана. */
    suspend fun updatePreferences(change: (Preferences) -> Preferences): Boolean {
        val owner = actionScope
        return if (owner == null) persistPreferenceChange(change)
        else owner.async { persistPreferenceChange(change) }.await()
    }

    private suspend fun persistPreferenceChange(change: (Preferences) -> Preferences): Boolean = try {
        core { updatePreferences(change) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        error = "saveFailed"
        false
    }

    suspend fun setProjectSort(mode: SortMode) = updatePreferences { it.copy(projectSort = mode) }
    suspend fun setNoteSort(mode: SortMode) = updatePreferences { it.copy(noteSort = mode) }
    suspend fun setTaskSort(mode: SortMode) = updatePreferences { it.copy(taskSort = mode) }

    suspend fun reorderProjects(ids: List<String>) = action { core { reorderProjects(ids) } }
    suspend fun reorderNotes(projectId: String, ids: List<String>) = action { core { reorderNotes(projectId, ids) } }
    suspend fun reorderTasks(ids: List<String>) = action {
        check(!taskArchive)
        core { reorderTasks(ids) }
    }

    fun projects(): List<Project> = content.projects()
    fun projectNotes(id: String): List<Note> = content.projectNotes(id)
    fun tasks(): List<Task> = content.tasks(taskArchive)
    fun orderedProjects(): List<Project> = content.destinationProjects()

    suspend fun startRecording() = controls { core { startRecording() } }
    suspend fun pauseRecording() = controls { core { pauseRecording() } }
    suspend fun resumeRecording() = controls { core { resumeRecording() } }
    suspend fun stopRecording() = controls { core { stopRecording() } }
    suspend fun recover() = controls { core { recover() } }
    suspend fun demo() = action { core { createDemo() } }

    internal suspend fun requestRecordingCancellation(expectedSessionId: String): Boolean = controls {
        val wasRecording = core { prepareRecordingCancellation(expectedSessionId) }
        deleteConfirmation = DeleteConfirmation.Recording(expectedSessionId, wasRecording)
    }

    internal fun requestCaptureDiscard(id: String) {
        if (!busy && !controlBusy && current?.id == id) deleteConfirmation = DeleteConfirmation.Capture(id)
    }

    internal fun requestTaskDeletion(id: String) {
        if (!busy && snapshot.tasks.any { it.id == id }) deleteConfirmation = DeleteConfirmation.Task(id)
    }

    internal fun dismissDeletion(target: DeleteConfirmation) {
        if (deleteConfirmation === target) deleteConfirmation = null
    }

    internal suspend fun keepDeletionTarget(target: DeleteConfirmation): Boolean {
        if (deleteConfirmation !== target) return false
        val kept = if (target is DeleteConfirmation.Recording && target.resumeOnKeep) {
            controls { core { resumeRecording(target.id) } }
        } else true
        if (kept) dismissDeletion(target)
        return kept
    }

    internal suspend fun confirmDeletion(target: DeleteConfirmation): Boolean {
        if (deleteConfirmation !== target) return false
        val deleted = target.execute(
            cancelRecording = { id -> controls { core { cancelActiveRecording(id) } } },
            discardCapture = { id -> discard(id) },
            deleteTask = { id -> deleteTask(id) },
        )
        if (deleted) dismissDeletion(target)
        return deleted
    }

    internal suspend fun cancelRecordingInCore(): Boolean {
        val target = deleteConfirmation as? DeleteConfirmation.Recording ?: return false
        return confirmDeletion(target)
    }

    internal suspend fun seekPlaybackInCore(seconds: Double): Boolean = controls { core { seekPlayback(seconds) } }

    suspend fun retry() = action { current?.let { capture -> core { retry(capture.id) } } }

    suspend fun tidy() = action {
        val capture = current ?: return@action
        core { tidy(capture.id) }
    }

    suspend fun sendToNotes() = action {
        val capture = current ?: return@action
        core { prepareNotes(capture.id) }
        choosingProject = true
        targetProjectId = null
    }

    suspend fun sendToTasks() = action {
        val capture = current ?: return@action
        core { prepareTask(capture.id) }
        taskScheduleTarget = "new"
    }

    suspend fun distribute(projectId: String, noteId: String? = null) = action {
        val capture = current ?: return@action
        core { distribute(capture.id, DistributionRequest(projectId, noteId)) }
        choosingProject = false
        targetProjectId = null
        tab = Tab.PROJECTS
        selectedProjectId = projectId
    }

    suspend fun saveTaskSchedule(dueAt: Long, repeat: ReminderRepeat) = action {
        val target = taskScheduleTarget ?: return@action
        if (target == "new") {
            val capture = current ?: return@action
            core { distributeTask(capture.id, TaskDistributionRequest(dueAt = dueAt, reminderRepeat = repeat)) }
            taskScheduleTarget = null
            tab = Tab.TASKS
            taskArchive = false
            selectedTaskId = null
        } else {
            core { rescheduleTask(target, TaskScheduleUpdate(dueAt, repeat)) }
            taskScheduleTarget = null
            selectedTaskId = target
        }
    }

    fun editTaskSchedule(id: String) { taskScheduleTarget = id }
    fun cancelTaskSchedule() { taskScheduleTarget = null }

    suspend fun discard(captureId: String? = current?.id) = action {
        val id = captureId ?: return@action
        core { discard(id) }
        choosingProject = false
        taskScheduleTarget = null
        tab = Tab.HOME
    }

    fun noteSources(id: String) = content.noteSources(id)

    fun openNote(id: String) {
        selectedNoteId = id
        editingNoteId = null
        application.selectNoteAudio(id)
        syncContent()
    }

    fun beginNoteEdit(id: String) { selectedNoteId = id; editingNoteId = id }
    fun cancelNoteEdit() { editingNoteId = null }

    suspend fun saveNote(id: String, body: String) = action {
        core { saveNote(id, body) }
        editingNoteId = null
    }

    fun openTask(id: String) { selectedTaskId = id }
    fun closeTask() { selectedTaskId = null }

    suspend fun saveTask(id: String, text: String) = action { core { saveTask(id, text) } }

    suspend fun completeTask(id: String) = action {
        core { completeTask(id) }
        selectedTaskId = null
        taskArchive = false
    }

    internal suspend fun completeTaskDetailInCore(id: String, workingText: String): Boolean = action {
        core { completeTaskFromDetail(id, workingText) }
        selectedTaskId = null
        taskArchive = false
    }

    suspend fun deleteTask(id: String) = action {
        core { deleteTask(id) }
        selectedTaskId = null
    }

    suspend fun pinNote(note: Note) = action { core { pinNote(note.id, !note.pinned) } }

    internal suspend fun moveNotePinInCore(note: Note, delta: Int): Boolean =
        core { moveNotePin(note.id, delta) }

    suspend fun requestListen(id: String) {
        controls {
            if (core { requestPlayback(id) } == PlaybackRequestResult.NEEDS_RECORDING_FINISH) confirmListenId = id
        }
    }

    suspend fun confirmStopAndListen() = controls {
        val id = confirmListenId ?: return@controls
        core { finishRecordingAndPlay(id) }
        confirmListenId = null
    }

    suspend fun play() = controls { core { play() } }
    suspend fun pausePlayback() = controls { core { pausePlayback() } }
    suspend fun resumePlayback() = controls { core { resumePlayback() } }
    fun stopPlayback() {
        try { application.stopPlayback() }
        catch (e: Exception) { error = e.message?.takeIf { Copy.has(it) } ?: "audioFailed" }
        finally { syncContent() }
    }

    suspend fun createProject(title: String, instruction: String) = action {
        val originCapture = creatingForCaptureId
        val project = core { createProject(ProjectDraft(title, instruction = instruction)) }
        if (editingProjectId == "new" && creatingForCaptureId == originCapture) {
            if (originCapture != null && choosingProject && current?.id == originCapture) targetProjectId = project.id
            editingProjectId = null
            creatingForCaptureId = null
        }
    }

    suspend fun updateProject(id: String, title: String, instruction: String) = action {
        core { updateProject(id, title, instruction) }
        editingProjectId = null
    }

    suspend fun pin(p: Project) = action { core { pinProject(p.id, !p.pinned) } }
    suspend fun movePin(p: Project, delta: Int) = action { core { moveProjectPin(p.id, delta) } }

    private suspend fun action(block: suspend () -> Unit): Boolean {
        val owner = actionScope
        return if (owner == null) performAction(block) else owner.async { performAction(block) }.await()
    }

    private suspend fun performAction(block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        val previous = current
        return try { block(); true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = actionFailureMessageKey(e.message, initialized); false }
        finally {
            syncContent()
            reconcileContentPresentation(previous)
            busy = false
        }
    }

    private suspend fun controls(block: suspend () -> Unit): Boolean {
        val owner = actionScope
        return if (owner == null) performControls(block) else owner.async { performControls(block) }.await()
    }

    private suspend fun performControls(block: suspend () -> Unit): Boolean {
        if (controlBusy) return false
        return try { block(); true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.takeIf { Copy.has(it) } ?: "audioFailed"; false }
        finally { syncContent() }
    }
}
