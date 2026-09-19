package brain.application

import brain.domain.*
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KashaBasicGuardsTest {
    private class Repo : StudioRepository {
        override val simulated = true
        var snapshot = AppSnapshot(projects = listOf(Project("p", "Проект")))
        override suspend fun snapshot() = snapshot
        override suspend fun preferences() = Preferences(autoRecord = true)
        override suspend fun savePreferences(value: Preferences) = error("unused")
        override suspend fun createProject(draft: ProjectDraft): Project = error("unused")
        override suspend fun updateProject(id: String, update: ProjectUpdate): Project = error("unused")
        override suspend fun pinProject(id: String, pinned: Boolean): Project = error("unused")
        override suspend fun orderPins(ids: List<String>) = error("unused")
        override suspend fun orderProjects(ids: List<String>) = error("unused")
        override suspend fun orderNotes(projectId: String, ids: List<String>) = error("unused")
        override suspend fun orderTasks(ids: List<String>) = error("unused")
        override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture = error("unused")
        override suspend fun distribute(id: String, request: DistributionRequest): Note = error("unused")
        override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = error("unused")
        override suspend fun updateNote(id: String, update: NoteUpdate): Note = error("unused")
        override suspend fun updateTask(id: String, update: TaskUpdate): Task = error("unused")
        override suspend fun completeTask(id: String): Task = error("unused")
        override suspend fun reprocess(id: String): Capture = error("unused")
        override suspend fun tidy(id: String): Capture = error("unused")
        override suspend fun rank(id: String): Capture = error("unused")
        override suspend fun discard(id: String) = error("unused")
        override suspend fun createDemo(): Capture = error("unused")
    }

    private class Recorder(private val repo: Repo) : RecorderSessionGateway {
        var permission = RecorderPermission.NOT_DETERMINED
        var state = RecorderSessionState()
        var pending = emptyList<PendingRecording>()
        var starts = 0
        override suspend fun permission() = permission
        override fun sessionState() = state
        override suspend fun pendingRecordings() = pending
        override fun level() = 0f
        override suspend fun start() { starts++; state = RecorderSessionState(RecorderPhase.RECORDING, "active") }
        override suspend fun pause() = error("unused")
        override suspend fun resume() = error("unused")
        override suspend fun stopAndUpload(): Capture = error("unused")
        override suspend fun cancelActive(sessionId: String) = error("unused")
        override suspend fun discardPending(pendingId: String) = error("unused")
        override suspend fun recoverPending(pendingId: String): Capture {
            check(pending.single().id == pendingId)
            val capture = Capture(pendingId, 1, status = CaptureStatus.QUEUED)
            repo.snapshot = repo.snapshot.copy(captures = listOf(capture))
            pending = emptyList()
            return capture
        }
    }

    private class Audio : PlaybackSessionGateway {
        override fun playbackState() = PlaybackSessionState()
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) = error("unused")
        override suspend fun pause() = error("unused")
        override suspend fun resume() = error("unused")
        override suspend fun seekTo(positionSeconds: Double): PlaybackSessionState = error("unused")
        override fun stop() = Unit
    }

    @Test fun autostartDoesNotRequestFirstPermission() = runTest {
        val repo = Repo(); val recorder = Recorder(repo); val app = KashaApplication(repo, recorder, Audio())
        app.launch("ru") { "Проект" }
        assertTrue(app.state.value.initialized)
        assertEquals(0, recorder.starts)
    }

    @Test fun deniedRestrictedAndUnavailableDoNotAutostart() = runTest {
        for (permission in listOf(RecorderPermission.DENIED, RecorderPermission.RESTRICTED, RecorderPermission.UNAVAILABLE)) {
            val repo = Repo(); val recorder = Recorder(repo).apply { this.permission = permission }
            val app = KashaApplication(repo, recorder, Audio())
            app.launch("ru") { "Проект" }
            assertTrue(app.state.value.initialized)
            assertEquals(0, recorder.starts)
        }
    }

    @Test fun permissionChangeDoesNotRepeatAutostartButExplicitStartWorks() = runTest {
        val repo = Repo(); val recorder = Recorder(repo); val app = KashaApplication(repo, recorder, Audio())
        app.launch("ru") { "Проект" }
        recorder.permission = RecorderPermission.GRANTED
        app.launch("ru") { "Проект" }
        assertEquals(0, recorder.starts)
        app.startRecording()
        assertEquals(1, recorder.starts)
    }

    @Test fun recoveryDoesNotRequireMicrophonePermission() = runTest {
        val repo = Repo(); val recorder = Recorder(repo).apply {
            permission = RecorderPermission.DENIED
            pending = listOf(PendingRecording("saved"))
        }
        val app = KashaApplication(repo, recorder, Audio())
        app.launch("ru") { "Проект" }
        assertEquals("saved", app.state.value.current?.id)
        assertEquals(0, recorder.starts)
    }

    @Test fun workingCaptureRejectsEditing() = runTest {
        for (status in CaptureStatus.entries.filter { it.isWorking }) {
            val repo = Repo().apply { snapshot = snapshot.copy(captures = listOf(Capture("c", 1, transcript = "Исходное", status = status))) }
            val app = KashaApplication(repo)
            app.refresh()
            assertFalse(app.editText("Правка во время обработки"))
            assertEquals("Исходное", app.state.value.edit.text)
            assertFalse(app.state.value.edit.dirty)
        }
    }

    @Test fun readyFailedAndMissingModelAllowManualEditing() = runTest {
        for (status in listOf(CaptureStatus.READY, CaptureStatus.FAILED, CaptureStatus.NEEDS_MODEL)) {
            val repo = Repo().apply { snapshot = snapshot.copy(captures = listOf(Capture("c", 1, status = status, audioFinalized = true))) }
            val app = KashaApplication(repo)
            app.refresh()
            assertTrue(app.editText("Ручной текст"))
            assertEquals("Ручной текст", app.state.value.edit.text)
        }
    }
}
