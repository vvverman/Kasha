package brain.domain

import brain.model.Capture
import brain.model.CaptureStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureRecoveryTest {
    @Test
    fun startEligibilityUsesTypedPermissionAndAllCaptureFacts() = runTest {
        val recorder = FakeRecorder()
        val coordinator = CaptureRecoveryCoordinator(recorder)

        recorder.permissionState = RecorderPermission.NOT_DETERMINED
        assertTrue(coordinator.snapshot(null).canAttemptNewRecording)

        recorder.permissionState = RecorderPermission.DENIED
        assertFalse(coordinator.snapshot(null).canAttemptNewRecording)

        recorder.permissionState = RecorderPermission.GRANTED
        recorder.pending += PendingRecording("pending")
        assertFalse(coordinator.snapshot(null).canAttemptNewRecording)
        assertFalse(coordinator.snapshot("current").canAttemptNewRecording)
    }

    @Test
    fun typedInterruptionIsPreservedWithoutPlatformDetails() = runTest {
        val recorder = FakeRecorder().apply {
            state = RecorderSessionState(
                phase = RecorderPhase.INTERRUPTED,
                activeSessionId = "session",
                issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = true),
            )
        }

        val snapshot = CaptureRecoveryCoordinator(recorder).snapshot(null)
        assertEquals(RecorderPhase.INTERRUPTED, snapshot.recorder.phase)
        assertEquals(RecorderIssueKind.INTERRUPTION, snapshot.recorder.issue?.kind)
        assertTrue(snapshot.recorder.issue?.recoverable == true)
        assertFalse(snapshot.canAttemptNewRecording)
    }

    @Test
    fun currentAndPendingAreKeptForSequentialRecovery() = runTest {
        val recorder = FakeRecorder().apply { pending += PendingRecording("pending") }
        val coordinator = CaptureRecoveryCoordinator(recorder)

        val snapshot = coordinator.snapshot("current")
        assertTrue(snapshot.requiresSequentialRecovery)
        assertNull(snapshot.automaticRecoveryCandidate)
        assertNull(coordinator.recoverAutomatically("current"))
        assertTrue(recorder.recovered.isEmpty())
    }

    @Test
    fun ambiguousPendingNeverAutoRecovers() = runTest {
        val recorder = FakeRecorder().apply {
            pending += PendingRecording("a")
            pending += PendingRecording("b")
        }
        val coordinator = CaptureRecoveryCoordinator(recorder)

        assertNull(coordinator.snapshot(null).automaticRecoveryCandidate)
        assertNull(coordinator.recoverAutomatically(null))
        assertTrue(recorder.recovered.isEmpty())
    }

    @Test
    fun exactPendingRecoveryRejectsCurrentAndWrongIdentity() = runTest {
        val recorder = FakeRecorder().apply { pending += PendingRecording("p1") }
        val coordinator = CaptureRecoveryCoordinator(recorder)

        assertFailsWith<IllegalArgumentException> { coordinator.recoverPending("current", "p1") }
        assertFailsWith<IllegalArgumentException> { coordinator.recoverPending(null, "other") }
        assertTrue(recorder.recovered.isEmpty())

        val capture = coordinator.recoverPending(null, "p1")
        assertEquals("capture-p1", capture.id)
        assertEquals(listOf("p1"), recorder.recovered)
    }

    @Test
    fun discardDeletesOnlySelectedPending() = runTest {
        val recorder = FakeRecorder().apply {
            pending += PendingRecording("keep")
            pending += PendingRecording("delete")
        }
        val coordinator = CaptureRecoveryCoordinator(recorder)

        coordinator.discardPending("delete")

        assertEquals(listOf("keep"), recorder.pending.map { it.id })
        assertEquals(listOf("delete"), recorder.discarded)
    }

    @Test
    fun cancelUsesExactActiveSessionAndNeverFinalizesCapture() = runTest {
        val recorder = FakeRecorder().apply {
            state = RecorderSessionState(RecorderPhase.RECORDING, "active-1")
            pending += PendingRecording("active-1")
        }
        val coordinator = CaptureRecoveryCoordinator(recorder)

        assertFailsWith<IllegalArgumentException> { coordinator.cancelActive("other") }
        assertTrue(recorder.cancelled.isEmpty())
        assertEquals(0, recorder.finalized)

        coordinator.cancelActive("active-1")

        assertEquals(listOf("active-1"), recorder.cancelled)
        assertEquals(RecorderPhase.IDLE, recorder.state.phase)
        assertTrue(recorder.pending.isEmpty())
        assertEquals(0, recorder.finalized)
        assertTrue(recorder.recovered.isEmpty())
    }

    private class FakeRecorder : RecorderSessionGateway {
        var permissionState = RecorderPermission.GRANTED
        var state = RecorderSessionState()
        val pending = mutableListOf<PendingRecording>()
        val cancelled = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var finalized = 0

        override suspend fun permission() = permissionState
        override fun sessionState() = state
        override suspend fun pendingRecordings() = pending.toList()

        override suspend fun cancelActive(sessionId: String) {
            require(state.activeSessionId == sessionId)
            cancelled += sessionId
            pending.removeAll { it.id == sessionId }
            state = RecorderSessionState()
        }

        override suspend fun recoverPending(pendingId: String): Capture {
            require(pending.any { it.id == pendingId })
            recovered += pendingId
            pending.removeAll { it.id == pendingId }
            return Capture("capture-$pendingId", 1, status = CaptureStatus.READY)
        }

        override suspend fun discardPending(pendingId: String) {
            require(pending.any { it.id == pendingId })
            discarded += pendingId
            pending.removeAll { it.id == pendingId }
        }

        override suspend fun start() {
            state = RecorderSessionState(RecorderPhase.RECORDING, "active")
            pending += PendingRecording("active")
        }

        override suspend fun pause() {
            state = state.copy(phase = RecorderPhase.PAUSED)
        }

        override suspend fun resume() {
            state = state.copy(phase = RecorderPhase.RECORDING)
        }

        override suspend fun stopAndUpload(): Capture {
            finalized++
            val id = state.activeSessionId ?: "finished"
            pending.removeAll { it.id == id }
            state = RecorderSessionState()
            return Capture("capture-$id", 1, status = CaptureStatus.READY)
        }
    }
}
