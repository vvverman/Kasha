package brain.application

import brain.domain.*
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.TimeSource
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
    val initialized: Boolean = false,
    val reminderDeliveryFailed: Boolean = false,
    val contentRefreshRequired: Boolean = false,
    val transport: KashaTransportState = KashaTransportState(),
) {
    val current: Capture? get() = snapshot.captures.firstOrNull { it.isInbox }
    val title: String get() = NoteText.title(edit.text)
    val canEdit: Boolean get() = current?.status?.isWorking == false && publishingCaptureId == null

    fun projects(): List<Project> = UserSort.projects(snapshot.projects, preferences.projectSort)
    fun projectNotes(id: String): List<Note> =
        UserSort.notes(snapshot.notes.filter { it.projectId == id }, preferences.noteSort)
    fun tasks(archive: Boolean): List<Task> = SnapshotQueries.tasks(snapshot.tasks, preferences.taskSort, archive)
    fun destinationProjects(): List<Project> = ProjectOrder.sorted(snapshot.projects, current?.relevance.orEmpty())
    fun noteSources(id: String): List<Capture> = snapshot.captures.filter { it.noteId == id }.sortedBy { it.appendedAt }
}

/**
 * Общий API контента, запуска и аудиотранспорта. Все оболочки используют одни команды.
 * Системные возможности приходят через существующие порты; Compose и ОС здесь нет.
 *
 * Команды сериализованы; редактирование во время autosave/AI не теряет новую версию текста.
 * UI наблюдает state и отправляет команды, но не изменяет snapshot напрямую.
 * Правила данных и идемпотентность остаются в существующих BrainData/StudioRepository.
 * Ошибки и отмена корутин передаются вызывающему; перевод и навигация принадлежат UI.
 */
class KashaApplication(
    private val repository: StudioRepository,
    private val recorder: RecorderGateway? = null,
    private val audio: AudioGateway? = null,
    private val reminders: ReminderGateway = NoopReminderGateway,
    private val monotonicMillis: () -> Long = monotonicClock(),
) {
    private val mutableState = MutableStateFlow(KashaApplicationState())
    val state: StateFlow<KashaApplicationState> = mutableState.asStateFlow()
    private val commands = Mutex()
    private val transportCommands = Mutex()
    private val polling = Mutex()
    private val playbackPreparation = MutableStateFlow<Job?>(null)
    private val playbackEpoch = MutableStateFlow(0L)
    private var lastCancelledSessionId: String? = null

    /** Повторный вызов не включает микрофон повторно. Неудачную загрузку данных можно повторить. */
    suspend fun launch(systemLanguage: String, firstProjectTitle: (String) -> String) =
        transportCommand(TransportOperation.LAUNCH) {
            if (mutableState.value.initialized) return@transportCommand
            commands.withLock {
                val preferences = repository.preferences().validated()
                mutableState.update { it.copy(preferences = preferences) }
                refreshUnlocked()
                if (mutableState.value.snapshot.projects.isEmpty()) {
                    val language = Languages.resolve(preferences.language, systemLanguage)
                    repository.createProject(ProjectDraft(firstProjectTitle(language)))
                    refreshUnlocked()
                }
            }
            refreshTransport()
            mutableState.update { it.copy(initialized = true) }
            val before = mutableState.value
            if (before.current != null || before.transport.recording) return@transportCommand
            if (before.transport.hasPending) {
                // Неоднозначные источники остаются на месте; автоматического выбора первого нет.
                val typed = recorder as? RecorderSessionGateway
                if (typed == null || before.transport.pendingRecordings.size == 1) recoverUnlocked(null)
            } else if (before.preferences.autoRecord && !before.transport.playback.occupied && requireRecorder().hasConsent()) {
                // Автозапуск не открывает системный запрос. Первый доступ запрашивается
                // только явным действием через существующую команду начала записи.
                startRecordingUnlocked()
            }
        }

    suspend fun startRecording() = transportCommand(TransportOperation.START) { startRecordingUnlocked() }

    private suspend fun startRecordingUnlocked() {
        check(mutableState.value.initialized) { "actionFailed" }
        // При прерванной финализации адаптер мог сохранить capture без возврата ответа.
        // До открытия микрофона повторно читаем подтверждённые данные, не только UI-кэш.
        refresh()
        refreshTransport()
        val before = mutableState.value
        check(before.current == null && !before.transport.hasPending) { "currentExists" }
        check(!before.transport.playback.occupied) { "stopPlayback" }
        check(!before.transport.recording) { "currentExists" }
        requireRecorder().start()
        sampleTransport(resetClock = true)
        check(mutableState.value.transport.recorderPhase == RecorderPhase.RECORDING) { "audioFailed" }
        mutableState.update { it.copy(transport = it.transport.copy(loadedAudioId = null, liveWave = List(80) { 0f })) }
    }

    suspend fun pauseRecording() = transportCommand(TransportOperation.PAUSE) {
        sampleTransport()
        check(mutableState.value.transport.recorderPhase == RecorderPhase.RECORDING) { "audioFailed" }
        requireRecorder().pause()
        sampleTransport()
        check(mutableState.value.transport.recorderPhase == RecorderPhase.PAUSED) { "audioFailed" }
    }

    suspend fun resumeRecording(expectedSessionId: String? = null) = transportCommand(TransportOperation.RESUME) {
        sampleTransport()
        if (expectedSessionId != null) {
            check(mutableState.value.transport.activeSessionId == expectedSessionId) { "audioFailed" }
        }
        check(mutableState.value.transport.recorderCanResume) { "audioFailed" }
        check(!mutableState.value.transport.playback.occupied) { "stopPlayback" }
        requireRecorder().resume()
        sampleTransport()
        check(mutableState.value.transport.recorderPhase == RecorderPhase.RECORDING) { "audioFailed" }
    }

    /** Подтверждение отмены относится к одной сессии; до его показа захват ставится на паузу. */
    suspend fun prepareRecordingCancellation(expectedSessionId: String): Boolean =
        transportCommand(TransportOperation.PAUSE) {
            val gateway = requireRecorder() as? RecorderSessionGateway
                ?: throw UnsupportedOperationException("audioFailed")
            val before = gateway.sessionState()
            check(before.activeSessionId == expectedSessionId &&
                before.phase in setOf(RecorderPhase.RECORDING, RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED)) { "audioFailed" }
            val wasRecording = before.phase == RecorderPhase.RECORDING
            if (wasRecording) gateway.pause()
            val after = gateway.sessionState()
            check(after.activeSessionId == expectedSessionId &&
                after.phase in setOf(RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED)) { "audioFailed" }
            wasRecording
        }

    suspend fun stopRecording(): Capture = transportCommand(TransportOperation.FINISH) { finishRecordingUnlocked() }

    private suspend fun finishRecordingUnlocked(): Capture {
        sampleTransport()
        check(mutableState.value.transport.recorderPhase in
            setOf(RecorderPhase.RECORDING, RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED)) { "audioFailed" }
        val capture = requireRecorder().stopAndUpload()
        // Адаптер может закончить сохранение даже после отмены вызывающего. Не запускаем
        // следующий шаг (например, playback); факты перечитываются в finally команды.
        currentCoroutineContext().ensureActive()
        refreshTransport()
        refresh()
        return capture
    }

    /** null разрешён только при единственном pending; адресованный вызов сохраняет остальные источники. */
    suspend fun recover(pendingId: String? = null): Capture =
        transportCommand(TransportOperation.RECOVER) { recoverUnlocked(pendingId) }

    private suspend fun recoverUnlocked(pendingId: String?): Capture = commands.withLock {
        check(mutableState.value.current == null) { "currentExists" }
        refreshTransport()
        // Восстановление готового файла не включает микрофон и не меняет плеер.
        check(!mutableState.value.transport.recording) { "audioFailed" }
        val gateway = requireRecorder()
        val capture = if (gateway is RecorderSessionGateway) {
            val id = pendingId ?: mutableState.value.transport.pendingRecordings.singleOrNull()?.id
                ?: error("audioFailed")
            CaptureRecoveryCoordinator(gateway).recoverPending(mutableState.value.current?.id, id)
        } else {
            check(pendingId == null) { "audioFailed" }
            check(mutableState.value.transport.hasPending) { "audioFailed" }
            gateway.recoverPending()
        }
        refreshTransport()
        refreshUnlocked()
        capture
    }

    /** Ожидаемый id фиксируется вызывающим до подтверждения; новая сессия не может быть удалена вместо старой. */
    suspend fun cancelActiveRecording(expectedSessionId: String) = transportCommand(TransportOperation.CANCEL) {
        val gateway = requireRecorder() as? RecorderSessionGateway
            ?: throw UnsupportedOperationException("audioFailed")
        val actual = gateway.sessionState()
        check(actual.phase != RecorderPhase.FINALIZING) { "audioFailed" }
        if (actual.phase == RecorderPhase.IDLE && lastCancelledSessionId == expectedSessionId) return@transportCommand
        CaptureRecoveryCoordinator(gateway).cancelActive(expectedSessionId)
        lastCancelledSessionId = expectedSessionId
        refreshTransport()
    }

    suspend fun requestPlayback(id: String): PlaybackRequestResult = transportCommand(TransportOperation.PLAY) {
        sampleTransport()
        if (mutableState.value.transport.recording) return@transportCommand PlaybackRequestResult.NEEDS_RECORDING_FINISH
        startPlaybackUnlocked(id)
        PlaybackRequestResult.STARTED
    }

    suspend fun finishRecordingAndPlay(id: String) = transportCommand(TransportOperation.FINISH) {
        finishRecordingUnlocked()
        startPlaybackUnlocked(id)
    }

    suspend fun play() = transportCommand(TransportOperation.PLAY) {
        val id = mutableState.value.transport.loadedAudioId ?: error("audioFailed")
        startPlaybackUnlocked(id)
    }

    private suspend fun startPlaybackUnlocked(id: String) {
        require(id.isNotBlank())
        sampleTransport()
        check(!mutableState.value.transport.recording) { "stopRecording" }
        mutableState.update { it.copy(transport = it.transport.copy(loadedAudioId = id)) }
        // Отдельный дочерний job позволяет Stop отменить загрузку, не отменяя владельца UI/приложения.
        coroutineScope {
            val epoch = playbackEpoch.value
            val task = async(start = CoroutineStart.LAZY) {
                check(playbackEpoch.value == epoch) { "audioFailed" }
                requireAudio().playCapture(id, false, 0.0, mutableState.value.transport.playbackRate)
            }
            playbackPreparation.value = task
            try {
                task.start()
                task.await()
            } catch (failure: Throwable) {
                try { requireAudio().stop() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            } finally {
                playbackPreparation.compareAndSet(task, null)
            }
        }
        sampleTransport()
    }

    suspend fun pausePlayback() = transportCommand(TransportOperation.PAUSE) {
        requireAudio().pause()
    }

    suspend fun resumePlayback() = transportCommand(TransportOperation.RESUME) {
        sampleTransport()
        check(!mutableState.value.transport.recording) { "stopRecording" }
        requireAudio().resume()
    }

    /** Не ждёт transport mutex: Stop обязан работать и во время приостановленной загрузки. */
    fun stopPlayback() {
        playbackEpoch.update { it + 1 }
        playbackPreparation.value?.cancel()
        requireAudio().stop()
        sampleTransport()
    }

    suspend fun seekPlayback(seconds: Double) = transportCommand(TransportOperation.SEEK) {
        sampleTransport()
        check(!mutableState.value.transport.recording) { "stopRecording" }
        val gateway = requireAudio() as? PlaybackSessionGateway
            ?: throw UnsupportedOperationException("audioFailed")
        val before = gateway.playbackState()
        val target = before.seekTarget(seconds) ?: error("audioFailed")
        val after = gateway.seekTo(target)
        check(after.sourceId == before.sourceId) { "audioFailed" }
        check(target >= before.durationSeconds || after.phase == before.phase) { "audioFailed" }
    }

    fun selectNoteAudio(noteId: String) {
        mutableState.update { before ->
            if (before.transport.recording || before.transport.playback.occupied || before.transport.operation != null) before
            else before.copy(transport = before.transport.copy(loadedAudioId = before.noteSources(noteId).firstOrNull()?.id))
        }
    }

    suspend fun createDemo(): Capture = transportCommand(TransportOperation.START) {
        refreshTransport()
        val before = mutableState.value
        check(!before.transport.recording && !before.transport.playback.occupied && !before.transport.hasPending && before.current == null) { "currentExists" }
        val capture = mutate { repository.createDemo() }
        mutableState.update { it.copy(transport = it.transport.copy(loadedAudioId = null)) }
        capture
    }

    /** Сохранение рабочей копии и выполнение — одна общая последовательность, не два UI-вызова. */
    suspend fun completeTaskFromDetail(id: String, workingText: String): Task = mutate {
        val task = mutableState.value.snapshot.tasks.firstOrNull { it.id == id } ?: error("actionFailed")
        check(!task.completed && workingText.isNotBlank()) { "actionFailed" }
        if (task.text != workingText) {
            val saved = repository.updateTask(id, TaskUpdate(workingText))
            check(saved.id == id) { "saveFailed" }
            // Сохранение уже подтверждено. Ошибка следующего действия не возвращает старый текст.
            mutableState.update { latest ->
                latest.copy(snapshot = latest.snapshot.copy(tasks = latest.snapshot.tasks.map {
                    if (it.id == saved.id) saved else it
                }))
            }
        }
        repository.completeTask(id)
    }

    /** Один структурированный цикл на экземпляр; отмена владельца останавливает все дочерние циклы. */
    suspend fun poll(onFailure: (ApplicationPollFailure) -> Unit) {
        if (!polling.tryLock()) return
        try {
            coroutineScope {
                launch {
                    while (isActive) {
                        delay(65)
                        try { refreshTransport(sampleLevel = true) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { onFailure(ApplicationPollFailure.TRANSPORT) }
                    }
                }
                launch {
                    while (isActive) {
                        delay(780)
                        if (!mutableState.value.contentRefreshRequired && mutableState.value.current?.status?.isWorking != true) continue
                        try { refresh() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { onFailure(ApplicationPollFailure.CONTENT) }
                    }
                }
                launch {
                    while (isActive) {
                        delay(9750)
                        try {
                            val managed = reminders.deliveryStatus()
                            if (managed != null) {
                                mutableState.update { it.copy(reminderDeliveryFailed = managed.failed || !managed.available) }
                                continue
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            mutableState.update { it.copy(reminderDeliveryFailed = true) }
                            onFailure(ApplicationPollFailure.REMINDERS)
                            continue
                        }
                        if (!reminders.available) continue
                        var delivered = false
                        try {
                            val due = repository.claimTaskReminders(Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault().id)
                            due.forEach { reminders.notify(it) }
                            delivered = due.isNotEmpty()
                            if (delivered) {
                                // Пустой опрос не подтверждает, что прежняя ошибка исчезла.
                                mutableState.update { it.copy(reminderDeliveryFailed = false) }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            mutableState.update { it.copy(reminderDeliveryFailed = true) }
                            onFailure(ApplicationPollFailure.REMINDERS)
                        }
                        if (delivered) {
                            try { refresh() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { onFailure(ApplicationPollFailure.CONTENT) }
                        }
                    }
                }
            }
        } finally { polling.unlock() }
    }

    suspend fun refreshTransport(sampleLevel: Boolean = false) {
        val revision = mutableState.value.transport.revision
        sampleTransport(sampleLevel = sampleLevel)
        if (mutableState.value.transport.recording) return
        val gateway = requireRecorder()
        val typed = gateway as? RecorderSessionGateway
        val pending = typed?.pendingRecordings().orEmpty()
        val hasPending = if (typed != null) pending.isNotEmpty() else gateway.hasPending()
        // За время чтения могли завершиться recovery/cancel и новая сессия.
        // Проверка revision внутри CAS не позволяет старому ответу вернуть удалённый pending.
        sampleTransport()
        mutableState.update { before ->
            if (before.transport.recording || before.transport.revision != revision) before
            else before.copy(transport = before.transport.copy(pendingRecordings = pending, hasPending = hasPending))
        }
    }

    private fun requireRecorder(): RecorderGateway = recorder ?: error("Recorder gateway is not configured")
    private fun requireAudio(): AudioGateway = audio ?: error("Audio gateway is not configured")

    private fun sampleTransport(resetClock: Boolean = false, sampleLevel: Boolean = false) {
        val gateway = requireRecorder()
        val actual = (gateway as? RecorderSessionGateway)?.sessionState()
        val phase = actual?.phase ?: RecorderPhase.entries.firstOrNull { it.legacyValue == gateway.phase() }
            ?: error("audioFailed")
        val now = monotonicMillis()
        val playback = readPlayback()
        val level = if (sampleLevel && phase == RecorderPhase.RECORDING) gateway.level().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f else null
        mutableState.update { before ->
            val old = before.transport
            var recorded = if (resetClock) 0L else old.recordedMillis
            var since = if (resetClock) null else old.runningSinceMillis
            if (phase != RecorderPhase.RECORDING && since != null) {
                recorded += (now - since).coerceAtLeast(0)
                since = null
            }
            if (phase == RecorderPhase.RECORDING && since == null) since = now
            val elapsed = recorded + (since?.let { (now - it).coerceAtLeast(0) } ?: 0L)
            before.copy(transport = old.copy(
                recorderPhase = phase,
                activeSessionId = actual?.activeSessionId,
                recorderIssue = actual?.issue,
                playback = playback,
                recordedMillis = recorded,
                runningSinceMillis = since,
                elapsedMillis = elapsed,
                liveWave = if (level != null) old.liveWave.drop(1) + level else old.liveWave,
            ))
        }
    }

    private fun readPlayback(): PlaybackSessionState {
        val gateway = requireAudio()
        if (gateway is PlaybackSessionGateway) return gateway.playbackState()
        val raw = gateway.telemetry()
        val phase = PlaybackPhase.fromLegacy(raw.phase)
        val sourceId = mutableState.value.transport.loadedAudioId
        // Legacy-порт не умеет сообщить id внешнего воспроизведения: UNKNOWN блокирует запись, id не выдумывается.
        if (phase != PlaybackPhase.IDLE && sourceId == null) return PlaybackSessionState(PlaybackPhase.UNKNOWN)
        val duration = raw.duration.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        val position = (raw.position.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0)
        return PlaybackSessionState(phase, sourceId, if (duration > 0) position.coerceAtMost(duration) else position,
            duration, (raw.level.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f))
    }

    private suspend fun <T> transportCommand(operation: TransportOperation, block: suspend () -> T): T =
        transportCommands.withLock {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(transport = it.transport.copy(operation = operation, revision = it.transport.revision + 1)) }
            var failure: Throwable? = null
            try { block() }
            catch (e: Throwable) { failure = e; throw e }
            finally {
                // Отсекаем ответы, начатые во время команды, до финального чтения адаптера.
                mutableState.update { it.copy(transport = it.transport.copy(revision = it.transport.revision + 1)) }
                try {
                    val rereadContent = failure != null && operation in setOf(TransportOperation.FINISH, TransportOperation.RECOVER)
                    if (rereadContent) mutableState.update { it.copy(contentRefreshRequired = true) }
                    reconcileTransportFinalizer(failure) {
                        // Только чтение и согласование. Здесь нет повторного stop/upload,
                        // восстановления, удаления или запуска AI/микрофона.
                        try { refreshTransport() }
                        finally {
                            if (rereadContent) refresh()
                        }
                    }
                } finally {
                    mutableState.update { it.copy(transport = it.transport.copy(operation = null, revision = it.transport.revision + 1)) }
                }
            }
        }


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

    /** Освобождение аудио и удаление источника — один сценарий Core. */
    suspend fun discard(captureId: String) = transportCommands.withLock { commands.withLock {
        requireCurrent(captureId)
        val selectedSource = mutableState.value.transport.loadedAudioId
        val actualSource = (audio as? PlaybackSessionGateway)?.playbackState()?.sourceId
        if (selectedSource == captureId || actualSource == captureId) {
            if (audio != null) stopPlayback()
            if (selectedSource == captureId) {
                mutableState.update { it.copy(transport = it.transport.copy(loadedAudioId = null)) }
            }
        }
        mutableState.update { it.copy(publishingCaptureId = captureId) }
        try {
            repository.discard(captureId)
            refreshUnlocked()
        } finally {
            mutableState.update { it.copy(publishingCaptureId = null) }
        }
    } }

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
                contentRefreshRequired = false,
                transport = if (current?.audioFinalized == true && before.transport.loadedAudioId == null && !before.transport.recording)
                    before.transport.copy(loadedAudioId = current.id) else before.transport,
                edit = if (edit.captureId == current?.id && edit.dirty) edit else
                    CaptureEditState(current?.id, current?.textToSave.orEmpty(), edit.revision, dirty = false),
            )
        }
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
