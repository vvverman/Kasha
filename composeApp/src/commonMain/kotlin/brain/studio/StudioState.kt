package brain.studio

import androidx.compose.runtime.*
import brain.application.KashaApplication
import brain.domain.*
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class Tab { HOME, PROJECTS, TASKS, SETTINGS }

class StudioState(
    val repository: StudioRepository,
    val recorder: RecorderGateway,
    val audio: AudioGateway,
    val systemLanguage: String = "ru",
    val reminders: ReminderGateway = NoopReminderGateway,
) {
    val application = KashaApplication(repository)
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
    var controlBusy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf(false)
    var confirmListenId by mutableStateOf<String?>(null)
    var recordPhase by mutableStateOf("idle"); private set
    var recorderIssue by mutableStateOf<RecorderIssue?>(null); private set
    var elapsed by mutableStateOf(0L); private set
    var liveWave by mutableStateOf(List(80) { 0f }); private set
    var playback by mutableStateOf(AudioTelemetry()); private set
    var playbackRate by mutableStateOf(1.0); private set
    var loadedAudioId by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf(false); private set
    var initialized by mutableStateOf(false); private set

    private var started = false
    private var recordedMillis = 0L
    private var mark: TimeMark? = null
    private var autoRouteFor: String? = null
    private var creatingForCaptureId: String? = null
    private var actionScope: CoroutineScope? = null
    private var contentObserver: Job? = null
    private val sessionRecorder: RecorderSessionGateway? get() = recorder as? RecorderSessionGateway

    fun attachActionScope(scope: CoroutineScope) {
        if (actionScope === scope) return
        contentObserver?.cancel()
        actionScope = scope
        contentObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            application.state.collect { syncContent() }
        }
    }

    fun detachActionScope(scope: CoroutineScope) {
        if (actionScope !== scope) return
        contentObserver?.cancel()
        contentObserver = null
        actionScope = null
    }

    private fun syncContent() { content = application.state.value }

    private suspend fun <T> core(block: suspend KashaApplication.() -> T): T =
        try { application.block() } finally { syncContent() }

    val recording get() = recordPhase == "recording" || recordPhase == "paused" || recordPhase == "interrupted" || recordPhase == "finalizing"
    val recorderCanResume get() = when (recordPhase) {
        "paused" -> recorderIssue?.recoverable != false
        "interrupted" -> recorderIssue?.recoverable == true
        else -> false
    }
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
        if (started) return
        started = true
        action {
            core { loadPreferences() }
            refresh()
            if (snapshot.projects.isEmpty()) {
                core { createProject(ProjectDraft(tr("firstProject"))) }
            }
            pending = hasPendingRecording()
            initialized = true
        }
        if (!initialized) return
        if (pending && current == null) recover()
        else if (preferences.autoRecord && current == null && !pending) startRecording()
    }

    suspend fun refresh() {
        val previous = current
        core { refresh() }
        reconcileContentPresentation(previous)
    }

    private fun reconcileContentPresentation(previous: Capture?) {
        val c = current
        if (c != null && c.audioFinalized && loadedAudioId == null && !recording) loadedAudioId = c.id
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

    suspend fun poll() {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(65)
            reconcileRecorderState()
            if (recording) {
                elapsed = (recordedMillis + (mark?.elapsedNow()?.inWholeMilliseconds ?: 0L)) / 1000
                if (recordPhase == "recording") {
                    val level = recorder.level().coerceIn(0f, 1f)
                    liveWave = liveWave.drop(1) + level
                }
            }
            playback = audio.telemetry()
            if (++tick % 12 == 0 && working) {
                try { refresh() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "actionFailed" }
            }
            if (tick % 150 == 0 && reminders.available) {
                try {
                    val due = repository.claimTaskReminders(
                        Clock.System.now().toEpochMilliseconds(),
                        TimeZone.currentSystemDefault().id,
                    )
                    due.forEach { reminders.notify(it) }
                    if (due.isNotEmpty()) refresh()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* напоминание не должно ломать приложение */ }
            }
        }
    }

    suspend fun savePreferences(value: Preferences) = action { core { savePreferences(value) } }
    suspend fun setProjectSort(mode: SortMode) = action { core { setProjectSort(mode) } }
    suspend fun setNoteSort(mode: SortMode) = action { core { setNoteSort(mode) } }
    suspend fun setTaskSort(mode: SortMode) = action { core { setTaskSort(mode) } }

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

    suspend fun startRecording() = controls {
        check(current == null && !pending) { "currentExists" }
        check(playback.phase == "idle") { "stopPlayback" }
        recorder.start()
        syncRecorderAfterControl()
        check(recordPhase == "recording") { "audioFailed" }
        recordedMillis = 0
        elapsed = 0
        mark = TimeSource.Monotonic.markNow()
        liveWave = List(80) { 0f }
        loadedAudioId = null
    }

    suspend fun pauseRecording() = controls {
        recorder.pause()
        freezeRecordingClock()
        syncRecorderAfterControl()
    }

    suspend fun resumeRecording() = controls {
        recorder.resume()
        syncRecorderAfterControl()
        check(recordPhase == "recording") { "audioFailed" }
        mark = TimeSource.Monotonic.markNow()
    }

    private suspend fun finishRecording() {
        try {
            recorder.stopAndUpload()
            mark = null
            elapsed = 0
            refresh()
        } finally {
            syncRecorderAfterControl()
            pending = hasPendingRecording()
        }
    }

    suspend fun stopRecording() = controls { finishRecording() }

    suspend fun recover() = controls {
        try {
            val typed = sessionRecorder
            if (typed != null) {
                val sources = typed.pendingRecordings()
                require(sources.size == 1) { "Expected exactly one pending recording" }
                typed.recoverPending(sources.single().id)
            } else {
                recorder.recoverPending()
            }
            refresh()
        } finally {
            pending = hasPendingRecording()
        }
    }

    suspend fun demo() = action {
        check(!recording && current == null && !pending && playback.phase == "idle") { "currentExists" }
        repository.createDemo()
        refresh()
        loadedAudioId = null
    }

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

    suspend fun discard() = action {
        val capture = current ?: return@action
        if (loadedAudioId == capture.id) {
            audio.stop(); loadedAudioId = null; playback = AudioTelemetry()
        }
        core { discard(capture.id) }
        confirmDelete = false
        choosingProject = false
        taskScheduleTarget = null
        tab = Tab.HOME
    }

    fun noteSources(id: String) = content.noteSources(id)

    fun openNote(id: String) {
        selectedNoteId = id
        editingNoteId = null
        if (!recording && playback.phase == "idle") loadedAudioId = noteSources(id).firstOrNull()?.id
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

    suspend fun deleteTask(id: String) = action {
        core { deleteTask(id) }
        selectedTaskId = null
    }

    suspend fun pinNote(note: Note) = action { core { pinNote(note.id, !note.pinned) } }

    internal suspend fun moveNotePinInCore(note: Note, delta: Int): Boolean =
        core { moveNotePin(note.id, delta) }

    suspend fun requestListen(id: String) {
        if (recording) { confirmListenId = id; return }
        controls { startPlayback(id) }
    }

    suspend fun confirmStopAndListen() = controls {
        val id = confirmListenId ?: return@controls
        finishRecording()
        confirmListenId = null
        startPlayback(id)
    }

    private suspend fun startPlayback(id: String, position: Double = 0.0) {
        val recorderIdle = sessionRecorder?.sessionState()?.phase == RecorderPhase.IDLE ||
            (sessionRecorder == null && recorder.phase() == "idle")
        check(!recording && recorderIdle) { "stopRecording" }
        audio.playCapture(id, false, position, playbackRate)
        loadedAudioId = id
        playback = audio.telemetry()
    }

    suspend fun play() = controls { loadedAudioId?.let { startPlayback(it) } }
    suspend fun pausePlayback() = controls { audio.pause(); playback = audio.telemetry() }
    suspend fun resumePlayback() = controls { check(!recording); audio.resume(); playback = audio.telemetry() }
    fun stopPlayback() { audio.stop(); playback = AudioTelemetry() }

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

    private suspend fun reconcileRecorderState() {
        val typed = sessionRecorder
        if (typed == null) {
            val phase = recorder.phase()
            if (recording && phase == "idle") {
                freezeRecordingClock()
                recordPhase = phase
                recorderIssue = null
                pending = recorder.hasPending()
            }
            return
        }
        val actual = typed.sessionState()
        val nextPhase = actual.phase.legacyValue
        if (recordPhase == "recording" && nextPhase != "recording") freezeRecordingClock()
        recordPhase = nextPhase
        recorderIssue = actual.issue
        if (actual.phase == RecorderPhase.IDLE) pending = typed.pendingRecordings().isNotEmpty()
    }

    private fun syncRecorderAfterControl() {
        val typed = sessionRecorder
        if (typed != null) {
            val actual = typed.sessionState()
            recordPhase = actual.phase.legacyValue
            recorderIssue = actual.issue
        } else {
            recordPhase = recorder.phase()
            recorderIssue = null
        }
    }

    private fun freezeRecordingClock() {
        val activeMark = mark
        if (activeMark != null) {
            recordedMillis += activeMark.elapsedNow().inWholeMilliseconds
            mark = null
        }
        elapsed = recordedMillis / 1000
    }

    private suspend fun hasPendingRecording(): Boolean =
        sessionRecorder?.pendingRecordings()?.isNotEmpty() ?: recorder.hasPending()

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
        catch (e: Exception) { error = e.message?.takeIf { Copy.has(it) } ?: "actionFailed"; false }
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
        controlBusy = true
        return try { block(); true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.takeIf { Copy.has(it) } ?: "audioFailed"; false }
        finally { controlBusy = false }
    }
}
