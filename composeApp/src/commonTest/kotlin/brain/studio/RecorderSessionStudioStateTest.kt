package brain.studio

import brain.domain.*
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderSessionStudioStateTest {
    private class Repo(
        var prefs: Preferences = Preferences(autoRecord = false),
    ) : StudioRepository {
        override val simulated = false
        private val project = Project("p", "Test")

        override suspend fun snapshot() = AppSnapshot(projects = listOf(project))
        override suspend fun preferences() = prefs
        override suspend fun savePreferences(value: Preferences) { prefs = value }

        override suspend fun createProject(draft: ProjectDraft) = error("unused")
        override suspend fun updateProject(id: String, update: ProjectUpdate) = error("unused")
        override suspend fun pinProject(id: String, pinned: Boolean) = error("unused")
        override suspend fun orderPins(ids: List<String>) = Unit
        override suspend fun orderProjects(ids: List<String>) = Unit
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate) = error("unused")
        override suspend fun distribute(id: String, request: DistributionRequest) = error("unused")
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest) = error("unused")
        override suspend fun updateNote(id: String, update: NoteUpdate) = error("unused")
        override suspend fun orderNotes(projectId: String, ids: List<String>) = Unit
        override suspend fun updateTask(id: String, update: TaskUpdate) = error("unused")
        override suspend fun orderTasks(ids: List<String>) = Unit
        override suspend fun reprocess(id: String) = error("unused")
        override suspend fun tidy(id: String) = error("unused")
        override suspend fun rank(id: String) = error("unused")
        override suspend fun discard(id: String) = Unit
        override suspend fun createDemo() = error("unused")
    }

    private class TypedRecorder : RecorderSessionGateway {
        var state = RecorderSessionState()
        var starts = 0
        var resumes = 0
        var levels = 0
        var recoveredId: String? = null
        var pendingSources = emptyList<PendingRecording>()

        override suspend fun permission() = RecorderPermission.GRANTED
        override fun sessionState() = state
        override suspend fun pendingRecordings() = pendingSources
        override fun level(): Float {
            levels++
            return 0.6f
        }

        override suspend fun start() {
            starts++
            state = RecorderSessionState(RecorderPhase.RECORDING, "active.m4a")
        }

        override suspend fun pause() {
            state = RecorderSessionState(RecorderPhase.PAUSED, state.activeSessionId ?: "active.m4a")
        }

        override suspend fun resume() {
            resumes++
            check(state.phase == RecorderPhase.PAUSED || state.issue?.recoverable == true)
            state = RecorderSessionState(RecorderPhase.RECORDING, state.activeSessionId ?: "active.m4a")
        }

        override suspend fun stopAndUpload(): Capture {
            state = RecorderSessionState()
            return Capture("capture", 1L)
        }

        override suspend fun cancelActive(sessionId: String) {
            check(sessionId == state.activeSessionId)
            state = RecorderSessionState()
        }

        override suspend fun recoverPending(pendingId: String): Capture {
            check(pendingSources.any { it.id == pendingId })
            recoveredId = pendingId
            pendingSources = pendingSources.filterNot { it.id == pendingId }
            return Capture("recovered", 1L)
        }

        override suspend fun discardPending(pendingId: String) {
            pendingSources = pendingSources.filterNot { it.id == pendingId }
        }

        fun interrupt(kind: RecorderIssueKind = RecorderIssueKind.INTERRUPTION, recoverable: Boolean = false) {
            state = RecorderSessionState(
                phase = RecorderPhase.INTERRUPTED,
                activeSessionId = state.activeSessionId ?: "active.m4a",
                issue = RecorderIssue(kind, recoverable),
            )
        }

        fun allowResume() {
            val issue = state.issue ?: RecorderIssue(RecorderIssueKind.INTERRUPTION)
            state = state.copy(issue = issue.copy(recoverable = true))
        }
    }

    private class Audio : AudioGateway {
        var plays = 0
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) {
            plays++
        }
        override fun telemetry() = AudioTelemetry()
        override fun stop() = Unit
    }

    @Test
    fun externalInterruptionFreezesWaveformAndRequiresExplicitResume() = runTest {
        val recorder = TypedRecorder()
        val state = StudioState(Repo(), recorder, Audio())
        state.launch()
        state.startRecording()
        val polling = backgroundScope.launch { state.poll() }

        advanceTimeBy(150); runCurrent()
        assertTrue(state.liveWave.any { it > 0f })
        val beforeInterruption = state.liveWave

        recorder.interrupt(recoverable = false)
        advanceTimeBy(150); runCurrent()

        assertEquals("interrupted", state.recordPhase)
        assertTrue(state.recording)
        assertFalse(state.recorderCanResume)
        assertEquals(beforeInterruption, state.liveWave)

        recorder.allowResume()
        advanceTimeBy(150); runCurrent()
        assertEquals("interrupted", state.recordPhase)
        assertTrue(state.recorderCanResume)
        assertEquals(0, recorder.resumes)
        assertEquals(beforeInterruption, state.liveWave)

        state.resumeRecording()
        assertEquals(1, recorder.resumes)
        assertEquals("recording", state.recordPhase)
        polling.cancel()
    }

    @Test
    fun interruptedRecorderStillBlocksPlayback() = runTest {
        val recorder = TypedRecorder()
        val audio = Audio()
        val state = StudioState(Repo(), recorder, audio)
        state.launch(); state.startRecording()
        val polling = backgroundScope.launch { state.poll() }

        recorder.interrupt(recoverable = true)
        advanceTimeBy(80); runCurrent()
        state.requestListen("saved")

        assertEquals("saved", state.confirmListenId)
        assertEquals(0, audio.plays)
        assertTrue(state.recording)
        polling.cancel()
    }

    @Test
    fun externalIdleRereadsPendingWithoutSecondAutostart() = runTest {
        val recorder = TypedRecorder()
        val state = StudioState(Repo(Preferences(autoRecord = true)), recorder, Audio())
        state.launch()
        assertEquals(1, recorder.starts)
        val polling = backgroundScope.launch { state.poll() }

        recorder.pendingSources = listOf(PendingRecording("crash.m4a"))
        recorder.state = RecorderSessionState()
        advanceTimeBy(150); runCurrent()

        assertEquals("idle", state.recordPhase)
        assertTrue(state.pending)
        assertEquals(1, recorder.starts)

        advanceTimeBy(300); runCurrent()
        assertEquals(1, recorder.starts)
        polling.cancel()
    }

    @Test
    fun recoveryUsesExactPendingIdentity() = runTest {
        val recorder = TypedRecorder().apply {
            pendingSources = listOf(PendingRecording("only.m4a"))
        }
        val state = StudioState(Repo(), recorder, Audio())

        state.launch()

        assertEquals("only.m4a", recorder.recoveredId)
        assertFalse(state.pending)
    }
}
