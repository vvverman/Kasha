package brain.application

import brain.domain.*
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KashaTransportTest {
    private class Repo : StudioRepository {
        override val simulated = true
        var prefs = Preferences(autoRecord = false)
        var data = BrainData(projects = listOf(Project("p", "Проект")))
        var failLoad = false
        var suspendSnapshot = false
        var failTaskSave = false
        var failComplete = false
        var failReminderClaim = false
        var dueReminders = emptyList<Task>()
        var onDiscard: ((String) -> Unit)? = null
        var snapshotGate: CompletableDeferred<Unit>? = null
        var projectCreates = 0
        val taskCalls = mutableListOf<String>()
        override suspend fun preferences() = prefs.also { check(!failLoad) }
        override suspend fun savePreferences(value: Preferences) { prefs = value }
        override suspend fun snapshot(): AppSnapshot {
            if (suspendSnapshot) yield()
            snapshotGate?.await()
            return AppSnapshot(projects = data.projects, notes = data.notes, captures = data.captures, tasks = data.tasks)
        }
        override suspend fun createProject(draft: ProjectDraft): Project {
            projectCreates++
            data = data.addProject("p$projectCreates", 1, draft)
            return data.projects.last()
        }
        override suspend fun updateProject(id: String, update: ProjectUpdate): Project = error("unused")
        override suspend fun pinProject(id: String, pinned: Boolean): Project = error("unused")
        override suspend fun orderPins(ids: List<String>) = Unit
        override suspend fun orderProjects(ids: List<String>) = Unit
        override suspend fun orderNotes(projectId: String, ids: List<String>) = Unit
        override suspend fun orderTasks(ids: List<String>) = Unit
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture = error("unused")
        override suspend fun distribute(id: String, request: DistributionRequest): Note = error("unused")
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = error("unused")
        override suspend fun updateNote(id: String, update: NoteUpdate): Note = error("unused")
        override suspend fun updateTask(id: String, update: TaskUpdate): Task {
            taskCalls += "save"
            check(!failTaskSave)
            data = data.updateTask(id, update, 2)
            return data.tasks.single()
        }
        override suspend fun completeTask(id: String): Task {
            taskCalls += "complete"
            check(!failComplete) { "complete failed" }
            data = data.completeTask(id, 3)
            return data.tasks.single()
        }
        override suspend fun reprocess(id: String): Capture = error("unused")
        override suspend fun tidy(id: String): Capture = error("unused")
        override suspend fun rank(id: String): Capture = error("unused")
        override suspend fun discard(id: String) {
            onDiscard?.invoke(id)
            data = data.copy(captures = data.captures.filterNot { it.id == id })
        }
        override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
            check(!failReminderClaim) { "reminder read failed" }
            return dueReminders.also { dueReminders = emptyList() }
        }
        override suspend fun createDemo(): Capture = capture("demo")
        fun capture(id: String): Capture = Capture(id, 1, status = CaptureStatus.QUEUED).also { data = data.addCapture(it) }
    }

    private class Recorder(private val repo: Repo) : RecorderSessionGateway {
        var state = RecorderSessionState()
        var pending = emptyList<PendingRecording>()
        var startGate: CompletableDeferred<Unit>? = null
        var finishGate: CompletableDeferred<Unit>? = null
        var acceptBeforeFinishGate = false
        var suspendPendingRead = false
        var nextPendingGate: CompletableDeferred<Unit>? = null
        var failPause = false
        var failFinish = false
        var failCancel = false
        var starts = 0
        var finishes = 0
        var pauses = 0
        var resumes = 0
        var levels = 0
        val recovered = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override suspend fun permission() = RecorderPermission.GRANTED
        override fun sessionState() = state
        override suspend fun pendingRecordings(): List<PendingRecording> {
            if (suspendPendingRead) yield()
            val snapshot = pending
            val gate = nextPendingGate
            nextPendingGate = null
            gate?.await()
            return snapshot
        }
        override fun level(): Float { levels++; return 0.5f }
        override suspend fun start() {
            starts++
            startGate?.await()
            state = RecorderSessionState(RecorderPhase.RECORDING, "s$starts")
        }
        override suspend fun pause() {
            pauses++
            check(!failPause)
            state = state.copy(phase = RecorderPhase.PAUSED)
        }
        override suspend fun resume() {
            resumes++
            state = state.copy(phase = RecorderPhase.RECORDING, issue = null)
        }
        override suspend fun stopAndUpload(): Capture {
            finishes++
            val id = state.activeSessionId!!
            state = RecorderSessionState()
            if (failFinish) { pending = pending + PendingRecording(id); error("audioFailed") }
            val gate = finishGate
            if (gate != null) {
                val accepted = if (acceptBeforeFinishGate) repo.capture(id) else null
                if (accepted == null) pending = pending + PendingRecording(id)
                gate.await()
                if (accepted != null) return accepted
                pending = pending.filterNot { it.id == id }
            }
            return repo.capture(id)
        }
        override suspend fun cancelActive(sessionId: String) {
            check(!failCancel && state.activeSessionId == sessionId)
            cancelled += sessionId
            state = RecorderSessionState()
        }
        override suspend fun recoverPending(pendingId: String): Capture {
            check(pending.any { it.id == pendingId })
            recovered += pendingId
            val capture = repo.capture(pendingId)
            pending = pending.filterNot { it.id == pendingId }
            return capture
        }
        override suspend fun discardPending(pendingId: String) { pending = pending.filterNot { it.id == pendingId } }
    }

    private class Audio : PlaybackSessionGateway {
        var state = PlaybackSessionState()
        var gate: CompletableDeferred<Unit>? = null
        var failPlay = false
        var wrongSeekReceipt = false
        var plays = 0
        var stops = 0
        override fun playbackState() = state
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
            state = PlaybackSessionState(PlaybackPhase.LOADING, captureId)
            gate?.await()
            check(!failPlay)
            plays++
            state = PlaybackSessionState(PlaybackPhase.PLAYING, captureId, fromSeconds, 10.0)
        }
        override suspend fun pause() { state = state.copy(phase = PlaybackPhase.PAUSED) }
        override suspend fun resume() { state = state.copy(phase = PlaybackPhase.PLAYING) }
        override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState {
            state = state.copy(positionSeconds = positionSeconds, sourceId = if (wrongSeekReceipt) "other" else state.sourceId)
            return state
        }
        override fun stop() { stops++; state = PlaybackSessionState() }
    }

    private class Reminders : ReminderGateway {
        override val available = true
        var failNotify = false
        var gate: CompletableDeferred<Unit>? = null
        val delivered = mutableListOf<String>()
        override suspend fun notify(task: Task) {
            gate?.await()
            check(!failNotify) { "reminder delivery failed" }
            delivered += task.id
        }
    }

    private class Fixture(val repo: Repo, val recorder: Recorder, val audio: Audio, val app: KashaApplication)
    private fun TestScope.fixture(reminders: ReminderGateway = NoopReminderGateway): Fixture {
        val repo = Repo(); val recorder = Recorder(repo); val audio = Audio()
        return Fixture(repo, recorder, audio, KashaApplication(repo, recorder, audio, reminders, monotonicMillis = { testScheduler.currentTime }))
    }
    private suspend fun KashaApplication.open() { launch("ru-RU") { "Первый проект" } }

    @Test fun disabledAutoRecordLoadsWithoutStartingMicrophone() = runTest {
        val f = fixture(); f.app.open()
        assertTrue(f.app.state.value.initialized)
        assertEquals(0, f.recorder.starts)
    }

    @Test fun repeatedAndConcurrentLaunchStartsOnlyOnce() = runTest {
        val f = fixture(); f.repo.prefs = Preferences(autoRecord = true)
        coroutineScope { repeat(3) { launch { f.app.open() } } }
        assertEquals(1, f.recorder.starts)
    }

    @Test fun failedInitializationIsRetryableAndNeverStartsMicrophone() = runTest {
        val f = fixture(); f.repo.failLoad = true
        assertFailsWith<IllegalStateException> { f.app.open() }
        assertFalse(f.app.state.value.initialized)
        assertEquals(0, f.recorder.starts)
        f.repo.failLoad = false; f.app.open()
        assertTrue(f.app.state.value.initialized)
    }

    @Test fun starterProjectUsesResolvedLanguageAndIsNotDuplicated() = runTest {
        val f = fixture(); f.repo.data = BrainData(); f.repo.prefs = Preferences(autoRecord = false, language = "de")
        f.app.launch("ru-RU") { language -> assertEquals("de", language); "Dein erstes Projekt" }
        f.app.open()
        assertEquals(1, f.repo.projectCreates)
        assertEquals("Dein erstes Projekt", f.repo.data.projects.single().title)
    }

    @Test fun existingCapturePreventsAutoRecord() = runTest {
        val f = fixture(); f.repo.prefs = Preferences(autoRecord = true); f.repo.capture("current")
        f.app.open()
        assertEquals(0, f.recorder.starts)
    }

    @Test fun onePendingIsRecoveredByIdentityBeforeAutoRecord() = runTest {
        val f = fixture(); f.repo.prefs = Preferences(autoRecord = true)
        f.recorder.pending = listOf(PendingRecording("saved")); f.app.open()
        assertEquals(listOf("saved"), f.recorder.recovered)
        assertEquals(0, f.recorder.starts)
        assertFalse(f.app.state.value.transport.hasPending)
        assertEquals("saved", f.app.state.value.current?.id)
    }

    @Test fun severalPendingArePreservedWithoutArbitraryAutomaticChoice() = runTest {
        val f = fixture(); f.repo.prefs = Preferences(autoRecord = true)
        f.recorder.pending = listOf(PendingRecording("a"), PendingRecording("b")); f.app.open()
        assertTrue(f.recorder.recovered.isEmpty())
        assertEquals(0, f.recorder.starts)
        assertEquals(listOf("a", "b"), f.app.state.value.transport.pendingRecordings.map { it.id })
    }

    @Test fun existingCaptureAndPendingAreBothPreserved() = runTest {
        val f = fixture(); f.repo.capture("current"); f.recorder.pending = listOf(PendingRecording("saved"))
        f.app.open()
        assertTrue(f.recorder.recovered.isEmpty())
        assertEquals("current", f.app.state.value.current?.id)
        assertTrue(f.app.state.value.transport.hasPending)
    }

    @Test fun explicitRecoveryOnlyConsumesSelectedPending() = runTest {
        val f = fixture(); f.recorder.pending = listOf(PendingRecording("a"), PendingRecording("b"))
        f.app.open(); f.app.recover("b")
        assertEquals(listOf("b"), f.recorder.recovered)
        assertEquals(listOf("a"), f.recorder.pending.map { it.id })
        assertFailsWith<IllegalStateException> { f.app.recover("a") }
    }

    @Test fun pauseFreezesClockAndWaveformAndResumeContinuesClock() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        advanceTimeBy(1250); f.app.refreshTransport(sampleLevel = true)
        assertEquals(1250L, f.app.state.value.transport.elapsedMillis)
        f.app.pauseRecording()
        val paused = f.app.state.value.transport
        advanceTimeBy(5000); f.app.refreshTransport(sampleLevel = true)
        assertEquals(paused.elapsedMillis, f.app.state.value.transport.elapsedMillis)
        assertEquals(paused.liveWave, f.app.state.value.transport.liveWave)
        f.app.resumeRecording(); advanceTimeBy(750); f.app.refreshTransport()
        assertEquals(2000L, f.app.state.value.transport.elapsedMillis)
    }

    @Test fun failedPauseDoesNotInventPausedStateOrFreezeClock() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.recorder.failPause = true
        advanceTimeBy(1000)
        assertFailsWith<IllegalStateException> { f.app.pauseRecording() }
        advanceTimeBy(1000); f.app.refreshTransport()
        assertEquals(RecorderPhase.RECORDING, f.app.state.value.transport.recorderPhase)
        assertEquals(2000L, f.app.state.value.transport.elapsedMillis)
    }

    @Test fun interruptionDoesNotResumeAutomatically() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        advanceTimeBy(1000)
        f.recorder.state = f.recorder.state.copy(phase = RecorderPhase.INTERRUPTED, issue = RecorderIssue(RecorderIssueKind.INTERRUPTION))
        f.app.refreshTransport()
        assertFalse(f.app.state.value.transport.recorderCanResume)
        f.recorder.state = f.recorder.state.copy(issue = f.recorder.state.issue!!.copy(recoverable = true))
        advanceTimeBy(5000); f.app.refreshTransport()
        assertEquals(1000L, f.app.state.value.transport.elapsedMillis)
        assertEquals(0, f.recorder.resumes)
        f.app.resumeRecording()
        assertEquals(1, f.recorder.resumes)
    }

    @Test fun occupiedRecorderRequestsConfirmationInsteadOfPlaying() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        for (phase in listOf(RecorderPhase.RECORDING, RecorderPhase.PAUSED, RecorderPhase.INTERRUPTED, RecorderPhase.FINALIZING)) {
            f.recorder.state = f.recorder.state.copy(phase = phase)
            assertEquals(PlaybackRequestResult.NEEDS_RECORDING_FINISH, f.app.requestPlayback("saved"))
        }
        assertEquals(0, f.audio.plays)
    }

    @Test fun pausedPlaybackBlocksMicrophoneEvenWithoutUiPolling() = runTest {
        val f = fixture(); f.app.open(); f.app.requestPlayback("saved"); f.app.pausePlayback()
        val error = assertFailsWith<IllegalStateException> { f.app.startRecording() }
        assertEquals("stopPlayback", error.message)
        assertEquals(0, f.recorder.starts)
    }

    @Test fun twoConcurrentStartCommandsCannotStartTwoRecorders() = runTest {
        val f = fixture(); f.app.open(); f.recorder.startGate = CompletableDeferred()
        val first = async { runCatching { f.app.startRecording() } }; runCurrent()
        val second = async { runCatching { f.app.startRecording() } }; runCurrent()
        f.recorder.startGate!!.complete(Unit)
        assertTrue(first.await().isSuccess); assertTrue(second.await().isFailure)
        assertEquals(1, f.recorder.starts)
    }

    @Test fun failedFinishImmediatelyExposesPendingAndClearsCommand() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.recorder.failFinish = true
        assertFailsWith<IllegalStateException> { f.app.stopRecording() }
        assertEquals(RecorderPhase.IDLE, f.app.state.value.transport.recorderPhase)
        assertTrue(f.app.state.value.transport.hasPending)
        assertNull(f.app.state.value.transport.operation)
    }

    @Test fun cancellationIsIdentityBoundAndNeverFinalizesCapture() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.app.cancelActiveRecording("s1"); f.app.cancelActiveRecording("s1")
        assertEquals(listOf("s1"), f.recorder.cancelled)
        assertEquals(0, f.recorder.finishes)
        assertTrue(f.repo.data.captures.isEmpty())
    }

    @Test fun staleCancellationCannotDeleteNewRecording() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.app.cancelActiveRecording("s1")
        f.app.startRecording()
        assertFailsWith<IllegalArgumentException> { f.app.cancelActiveRecording("s1") }
        assertEquals("s2", f.recorder.state.activeSessionId)
    }

    @Test fun failedCancellationKeepsActualSession() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.recorder.failCancel = true
        assertFailsWith<IllegalStateException> { f.app.cancelActiveRecording("s1") }
        assertEquals("s1", f.app.state.value.transport.activeSessionId)
        assertNull(f.app.state.value.transport.operation)
    }

    @Test fun seekPreservesPausedSourceAndClampsPosition() = runTest {
        val f = fixture(); f.app.open(); f.app.requestPlayback("saved"); f.app.pausePlayback()
        f.app.seekPlayback(4.0)
        assertEquals(PlaybackPhase.PAUSED, f.app.state.value.transport.playback.phase)
        assertEquals("saved", f.app.state.value.transport.playback.sourceId)
        assertEquals(4.0, f.app.state.value.transport.playback.positionSeconds)
        f.app.seekPlayback(100.0)
        assertEquals(10.0, f.app.state.value.transport.playback.positionSeconds)
    }

    @Test fun wrongSeekReceiptIsNotReportedAsSuccess() = runTest {
        val f = fixture(); f.app.open(); f.app.requestPlayback("saved"); f.audio.wrongSeekReceipt = true
        assertFailsWith<IllegalStateException> { f.app.seekPlayback(2.0) }
    }

    @Test fun stopCancelsPendingPlaybackWithoutLateStart() = runTest {
        val f = fixture(); f.app.open(); f.audio.gate = CompletableDeferred()
        val preparing = launch { runCatching { f.app.requestPlayback("saved") } }; runCurrent()
        assertEquals(PlaybackPhase.LOADING, f.audio.state.phase)
        f.app.stopPlayback(); runCurrent(); preparing.join()
        f.audio.gate!!.complete(Unit); runCurrent()
        assertEquals(0, f.audio.plays)
        assertEquals(PlaybackPhase.IDLE, f.app.state.value.transport.playback.phase)
        assertEquals("saved", f.app.state.value.transport.loadedAudioId)
        assertNull(f.app.state.value.transport.operation)
    }

    @Test fun playbackErrorReleasesAdapterAndCommand() = runTest {
        val f = fixture(); f.app.open(); f.audio.failPlay = true
        assertFailsWith<IllegalStateException> { f.app.requestPlayback("saved") }
        assertEquals(PlaybackPhase.IDLE, f.audio.state.phase)
        assertNull(f.app.state.value.transport.operation)
    }

    @Test fun finishAndListenPreservesRecordingBeforePlaying() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.app.finishRecordingAndPlay("saved")
        assertEquals("s1", f.repo.data.captures.single().id)
        assertEquals("saved", f.app.state.value.transport.playback.sourceId)
        assertEquals(1, f.recorder.finishes)
        assertEquals(1, f.audio.plays)
    }

    @Test fun repeatedPollingDoesNotCreateSecondTimer() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        val first = backgroundScope.launch { f.app.poll { fail("Unexpected poll error: $it") } }
        val second = backgroundScope.launch { f.app.poll { fail("Unexpected poll error: $it") } }
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(3, f.recorder.levels)
        assertTrue(second.isCompleted)
        first.cancelAndJoin()
    }

    @Test fun slowContentReadDoesNotBlockTransportPolling() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.repo.snapshotGate = CompletableDeferred()
        val read = backgroundScope.launch { f.app.refresh() }
        val poll = backgroundScope.launch { f.app.poll { fail("Unexpected poll error: $it") } }
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertFalse(read.isCompleted)
        assertEquals(3, f.recorder.levels)
        f.repo.snapshotGate!!.complete(Unit); read.join(); poll.cancelAndJoin()
    }

    @Test fun cancellingPollingStopsChildrenButDoesNotCancelAudioSession() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        val poll = backgroundScope.launch { f.app.poll { fail("Unexpected poll error: $it") } }
        runCurrent(); advanceTimeBy(200); runCurrent(); poll.cancelAndJoin()
        val samples = f.recorder.levels
        advanceTimeBy(1000); runCurrent()
        assertEquals(samples, f.recorder.levels)
        assertEquals(RecorderPhase.RECORDING, f.recorder.state.phase)
    }

    @Test fun completionSavesWorkingCopyBeforeCompletingInCore() = runTest {
        val f = fixture(); f.repo.data = f.repo.data.copy(tasks = listOf(Task("t", text = "Старое", createdAt = 1, updatedAt = 1)))
        f.app.open(); f.app.completeTaskFromDetail("t", "Новое")
        assertEquals(listOf("save", "complete"), f.repo.taskCalls)
        assertEquals("Новое", f.repo.data.tasks.single().text)
        assertTrue(f.repo.data.tasks.single().completed)
    }

    @Test fun failedWorkingCopySavePreventsCompletionInCore() = runTest {
        val f = fixture(); f.repo.data = f.repo.data.copy(tasks = listOf(Task("t", text = "Старое", createdAt = 1, updatedAt = 1)))
        f.app.open(); f.repo.failTaskSave = true
        assertFailsWith<IllegalStateException> { f.app.completeTaskFromDetail("t", "Новое") }
        assertEquals(listOf("save"), f.repo.taskCalls)
        assertFalse(f.repo.data.tasks.single().completed)
    }

    @Test fun legacyRecorderDoesNotPretendToSupportCancellation() = runTest {
        val f = fixture()
        val legacy = object : RecorderGateway by f.recorder {}
        val app = KashaApplication(f.repo, legacy, f.audio)
        app.open(); app.startRecording()
        assertFailsWith<UnsupportedOperationException> { app.cancelActiveRecording("s1") }
        assertEquals(RecorderPhase.RECORDING, f.recorder.state.phase)
    }

    @Test fun latePendingReadCannotRestoreRecoveredSource() = runTest {
        val f = fixture()
        f.recorder.pending = listOf(PendingRecording("a"), PendingRecording("b"))
        f.app.open()
        val gate = CompletableDeferred<Unit>()
        f.recorder.nextPendingGate = gate
        val oldRead = async { f.app.refreshTransport() }
        runCurrent()
        f.app.recover("b")
        assertEquals(listOf("a"), f.app.state.value.transport.pendingRecordings.map { it.id })
        gate.complete(Unit)
        oldRead.await()
        assertEquals(listOf("a"), f.app.state.value.transport.pendingRecordings.map { it.id })
    }

    @Test fun lateEmptyReadCannotHideNewRecoverableRecording() = runTest {
        val f = fixture(); f.app.open()
        val gate = CompletableDeferred<Unit>()
        f.recorder.nextPendingGate = gate
        val oldRead = async { f.app.refreshTransport() }
        runCurrent()
        f.app.startRecording()
        f.recorder.failFinish = true
        assertFailsWith<IllegalStateException> { f.app.stopRecording() }
        gate.complete(Unit)
        oldRead.await()
        assertTrue(f.app.state.value.transport.hasPending)
        assertEquals(listOf("s1"), f.app.state.value.transport.pendingRecordings.map { it.id })
    }

    @Test fun failedCompletionKeepsAlreadySavedWorkingTextInState() = runTest {
        val f = fixture()
        f.repo.data = f.repo.data.copy(tasks = listOf(Task("t", text = "Старое", createdAt = 1, updatedAt = 1)))
        f.app.open(); f.repo.failComplete = true
        assertFailsWith<IllegalStateException> { f.app.completeTaskFromDetail("t", "Новое") }
        assertEquals("Новое", f.app.state.value.snapshot.tasks.single().text)
        assertFalse(f.app.state.value.snapshot.tasks.single().completed)
        assertEquals(listOf("save", "complete"), f.repo.taskCalls)
        f.repo.failComplete = false
        f.app.completeTaskFromDetail("t", "Новое")
        assertEquals(listOf("save", "complete", "complete"), f.repo.taskCalls)
    }

    @Test fun discardStopsActualSourceWhenSelectionIsDifferent() = runTest {
        val f = fixture()
        f.repo.capture("current")
        f.app.open(); f.app.requestPlayback("selected")
        // Системное событие ещё не получено polling: выделение не равно реальному источнику.
        f.audio.state = PlaybackSessionState(PlaybackPhase.PLAYING, "current", 0.0, 10.0)
        f.repo.onDiscard = { id ->
            assertEquals("current", id)
            assertEquals(PlaybackPhase.IDLE, f.audio.state.phase)
        }
        f.app.discard("current")
        assertEquals(1, f.audio.stops)
        assertNull(f.app.state.value.current)
        assertEquals("selected", f.app.state.value.transport.loadedAudioId)
    }

    @Test fun startupRecoveryPreservesExistingPlayback() = runTest {
        val f = fixture()
        f.repo.prefs = Preferences(autoRecord = true)
        f.recorder.pending = listOf(PendingRecording("saved"))
        val playing = PlaybackSessionState(PlaybackPhase.PAUSED, "playing", 2.0, 10.0)
        f.audio.state = playing
        f.app.open()
        assertTrue(f.app.state.value.initialized)
        assertFalse(f.app.state.value.transport.hasPending)
        assertEquals(listOf("saved"), f.recorder.recovered)
        assertEquals("saved", f.app.state.value.current?.id)
        assertEquals(0, f.recorder.starts)
        assertEquals(0, f.audio.stops)
        assertEquals(playing, f.audio.state)
    }

    @Test fun manualRecoveryPreservesPlayingAndPausedSource() = runTest {
        for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED)) {
            val f = fixture(); f.app.open()
            val playing = PlaybackSessionState(phase, "another-source", 3.0, 10.0)
            f.audio.state = playing
            f.recorder.pending = listOf(PendingRecording("ready-file"))
            assertEquals("ready-file", f.app.recover("ready-file").id)
            assertEquals(listOf("ready-file"), f.recorder.recovered)
            assertEquals(playing, f.audio.state)
            assertEquals(playing, f.app.state.value.transport.playback)
            assertEquals(0, f.audio.stops)
            assertEquals(0, f.audio.plays)
            assertEquals(0, f.recorder.starts)
        }
    }

    @Test fun recoveryStillRejectsActiveMicrophone() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.pending = listOf(PendingRecording("ready-file"))
        assertFailsWith<IllegalStateException> { f.app.recover("ready-file") }
        assertTrue(f.recorder.recovered.isEmpty())
        assertEquals(RecorderPhase.RECORDING, f.recorder.state.phase)
        assertEquals(listOf("ready-file"), f.recorder.pending.map { it.id })
    }

    @Test fun reminderDeliveryFailurePreservesTasksAndPlayback() = runTest {
        val reminders = Reminders().apply { failNotify = true }
        val f = fixture(reminders)
        val task = Task("t", text = "Сохранённый текст", createdAt = 1, updatedAt = 1)
        f.repo.data = f.repo.data.copy(tasks = listOf(task)); f.app.open()
        f.repo.dueReminders = listOf(task)
        val playing = PlaybackSessionState(PlaybackPhase.PLAYING, "voice", 2.0, 10.0)
        f.audio.state = playing
        val failures = mutableListOf<ApplicationPollFailure>()
        val poll = backgroundScope.launch { f.app.poll { failures += it } }
        runCurrent(); advanceTimeBy(9751); runCurrent()
        assertTrue(f.app.state.value.reminderDeliveryFailed)
        assertEquals(listOf(ApplicationPollFailure.REMINDERS), failures)
        assertEquals(listOf(task), f.repo.data.tasks)
        assertEquals(playing, f.audio.state)
        assertEquals(0, f.audio.stops)
        poll.cancelAndJoin()
    }

    @Test fun emptyReminderPollDoesNotEraseFailure() = runTest {
        val reminders = Reminders().apply { failNotify = true }
        val f = fixture(reminders); f.app.open()
        f.repo.dueReminders = listOf(Task("t", text = "Текст", createdAt = 1, updatedAt = 1))
        val poll = backgroundScope.launch { f.app.poll { } }
        runCurrent(); advanceTimeBy(9751); runCurrent()
        assertTrue(f.app.state.value.reminderDeliveryFailed)
        reminders.failNotify = false
        advanceTimeBy(9750); runCurrent()
        assertTrue(f.app.state.value.reminderDeliveryFailed)
        assertTrue(reminders.delivered.isEmpty())
        poll.cancelAndJoin()
    }

    @Test fun laterSuccessfulReminderClearsDeliveryFailure() = runTest {
        val reminders = Reminders().apply { failNotify = true }
        val f = fixture(reminders); f.app.open()
        f.repo.dueReminders = listOf(Task("first", text = "Первое", createdAt = 1, updatedAt = 1))
        val poll = backgroundScope.launch { f.app.poll { } }
        runCurrent(); advanceTimeBy(9751); runCurrent()
        assertTrue(f.app.state.value.reminderDeliveryFailed)
        reminders.failNotify = false
        f.repo.dueReminders = listOf(Task("next", text = "Следующее", createdAt = 2, updatedAt = 2))
        advanceTimeBy(9750); runCurrent()
        assertFalse(f.app.state.value.reminderDeliveryFailed)
        assertEquals(listOf("next"), reminders.delivered)
        poll.cancelAndJoin()
    }

    @Test fun failedReminderReadRetainsFailureState() = runTest {
        val f = fixture(Reminders()); f.app.open(); f.repo.failReminderClaim = true
        val poll = backgroundScope.launch { f.app.poll { } }
        runCurrent(); advanceTimeBy(9751); runCurrent()
        assertTrue(f.app.state.value.reminderDeliveryFailed)
        poll.cancelAndJoin()
    }

    @Test fun cancelledReminderDeliveryDoesNotInventFailure() = runTest {
        val reminders = Reminders().apply { gate = CompletableDeferred() }
        val f = fixture(reminders); f.app.open()
        f.repo.dueReminders = listOf(Task("t", text = "Текст", createdAt = 1, updatedAt = 1))
        val failures = mutableListOf<ApplicationPollFailure>()
        val poll = backgroundScope.launch { f.app.poll { failures += it } }
        runCurrent(); advanceTimeBy(9751); runCurrent()
        poll.cancelAndJoin()
        assertFalse(f.app.state.value.reminderDeliveryFailed)
        assertTrue(failures.isEmpty())
    }

    @Test fun preparingCancellationPausesOnlyTheExpectedSession() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        assertTrue(f.app.prepareRecordingCancellation("s1"))
        assertEquals(RecorderPhase.PAUSED, f.recorder.state.phase)
        assertEquals(1, f.recorder.pauses)
        assertEquals(0, f.recorder.finishes)
        assertTrue(f.recorder.cancelled.isEmpty())
    }

    @Test fun preparingAlreadyPausedCancellationDoesNotResumeIt() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.app.pauseRecording()
        assertFalse(f.app.prepareRecordingCancellation("s1"))
        assertEquals(1, f.recorder.pauses)
        assertEquals(0, f.recorder.resumes)
    }

    @Test fun failedPauseDoesNotPretendConfirmationIsSafe() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording(); f.recorder.failPause = true
        assertFailsWith<IllegalStateException> { f.app.prepareRecordingCancellation("s1") }
        assertEquals(RecorderPhase.RECORDING, f.app.state.value.transport.recorderPhase)
        assertTrue(f.recorder.cancelled.isEmpty())
    }

    @Test fun staleConfirmationCannotPauseOrResumeReplacementSession() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.state = RecorderSessionState(RecorderPhase.RECORDING, "replacement")
        assertFailsWith<IllegalStateException> { f.app.prepareRecordingCancellation("s1") }
        assertEquals(0, f.recorder.pauses)
        f.recorder.state = f.recorder.state.copy(phase = RecorderPhase.PAUSED)
        assertFailsWith<IllegalStateException> { f.app.resumeRecording("s1") }
        assertEquals(0, f.recorder.resumes)
        assertEquals("replacement", f.recorder.state.activeSessionId)
    }

    @Test fun finalizingSessionCannotBeFinishedAgainOrDeleted() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.state = f.recorder.state.copy(phase = RecorderPhase.FINALIZING)
        assertFailsWith<IllegalStateException> { f.app.stopRecording() }
        assertFailsWith<IllegalStateException> { f.app.cancelActiveRecording("s1") }
        assertEquals(0, f.recorder.finishes)
        assertTrue(f.recorder.cancelled.isEmpty())
        assertEquals(RecorderPhase.FINALIZING, f.app.state.value.transport.recorderPhase)
    }

    @Test fun cancellingFinishReconcilesSuspendingPendingReadWithoutRecovery() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.finishGate = CompletableDeferred()
        f.recorder.suspendPendingRead = true
        val job = launch { f.app.stopRecording() }
        runCurrent(); job.cancelAndJoin()
        assertEquals(listOf("s1"), f.app.state.value.transport.pendingRecordings.map { it.id })
        assertTrue(f.app.state.value.transport.hasPending)
        assertNull(f.app.state.value.transport.operation)
        assertEquals(1, f.recorder.finishes)
        assertTrue(f.recorder.recovered.isEmpty())
        assertTrue(f.recorder.cancelled.isEmpty())
        assertTrue(f.repo.data.captures.isEmpty())
    }

    @Test fun cancellingAfterCaptureCommitReconcilesDataWithoutSecondFinalization() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.finishGate = CompletableDeferred(); f.recorder.acceptBeforeFinishGate = true
        f.repo.suspendSnapshot = true
        val job = launch { f.app.stopRecording() }
        runCurrent(); job.cancelAndJoin()
        assertEquals("s1", f.app.state.value.current?.id)
        assertFalse(f.app.state.value.contentRefreshRequired)
        assertNull(f.app.state.value.transport.operation)
        assertEquals(1, f.recorder.finishes)
        assertEquals(1, f.repo.data.captures.size)
        assertFailsWith<IllegalStateException> { f.app.startRecording() }
        assertEquals(1, f.recorder.starts)
    }

    @Test fun cancellingFinishAndListenDoesNotStartPlayback() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.finishGate = CompletableDeferred(); f.recorder.acceptBeforeFinishGate = true
        val job = launch { f.app.finishRecordingAndPlay("saved-source") }
        runCurrent(); job.cancelAndJoin()
        assertEquals(0, f.audio.plays)
        assertEquals("s1", f.app.state.value.current?.id)
        assertEquals(1, f.recorder.finishes)
    }

    @Test fun failedPostCancellationReadIsRetriedBeforeAnotherRecording() = runTest {
        val f = fixture(); f.app.open(); f.app.startRecording()
        f.recorder.finishGate = CompletableDeferred(); f.recorder.acceptBeforeFinishGate = true
        val job = launch { f.app.stopRecording() }
        runCurrent()
        f.repo.snapshotGate = CompletableDeferred()
        job.cancel(); advanceTimeBy(5_001); runCurrent(); job.join()
        assertTrue(f.app.state.value.contentRefreshRequired)
        assertNull(f.app.state.value.transport.operation)
        f.repo.snapshotGate = null
        assertFailsWith<IllegalStateException> { f.app.startRecording() }
        assertEquals("s1", f.app.state.value.current?.id)
        assertEquals(1, f.recorder.starts)
        assertEquals(1, f.recorder.finishes)
    }

}
