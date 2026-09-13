package brain.domain

import brain.model.Capture
import brain.model.CaptureStatus
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RecorderContractTest {
    @Test
    fun activePhaseRequiresStableOpaqueSessionId() {
        assertFails { RecorderSessionState(RecorderPhase.RECORDING) }
        assertFails { RecorderSessionState(RecorderPhase.PAUSED, " ") }

        val state = RecorderSessionState(
            phase = RecorderPhase.INTERRUPTED,
            activeSessionId = "session-42",
            issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = true),
        )
        assertEquals("session-42", state.activeSessionId)
        assertTrue(state.issue?.recoverable == true)
    }

    @Test
    fun pendingRecordingRejectsAmbiguousIdentityAndInvalidMetadata() {
        assertFails { PendingRecording("") }
        assertFails { PendingRecording("p", durationMillis = -1) }
        assertEquals("p", PendingRecording("p", createdAt = 0, durationMillis = 0).id)
    }

    @Test
    fun legacyRecoveryBridgeNeverChoosesAmongSeveralPendingSources() {
        val recorder = ExactRecorder(
            pending = mutableListOf(PendingRecording("a"), PendingRecording("b")),
        )
        assertFails { runSuspend { recorder.recoverPending() } }
        assertTrue(recorder.recovered.isEmpty())
    }

    @Test
    fun legacyRecoveryBridgeUsesTheOnlyExactPendingId() {
        val recorder = ExactRecorder(pending = mutableListOf(PendingRecording("only")))
        val capture = runSuspend { recorder.recoverPending() }
        assertEquals("only", recorder.recovered.single())
        assertEquals("capture-only", capture.id)
    }

    private class ExactRecorder(
        val pending: MutableList<PendingRecording> = mutableListOf(),
    ) : RecorderSessionGateway {
        var state = RecorderSessionState()
        val recovered = mutableListOf<String>()

        override suspend fun permission() = RecorderPermission.GRANTED
        override fun sessionState() = state
        override suspend fun pendingRecordings() = pending.toList()
        override suspend fun cancelActive(sessionId: String) { state = RecorderSessionState() }
        override suspend fun discardPending(pendingId: String) { pending.removeAll { it.id == pendingId } }
        override suspend fun recoverPending(pendingId: String): Capture {
            require(pending.any { it.id == pendingId })
            recovered += pendingId
            pending.removeAll { it.id == pendingId }
            return Capture(
                id = "capture-$pendingId",
                createdAt = 1,
                transcript = "text",
                status = CaptureStatus.READY,
            )
        }

        override suspend fun start() { state = RecorderSessionState(RecorderPhase.RECORDING, "active") }
        override suspend fun pause() { state = RecorderSessionState(RecorderPhase.PAUSED, "active") }
        override suspend fun resume() { state = RecorderSessionState(RecorderPhase.RECORDING, "active") }
        override suspend fun stopAndUpload(): Capture = Capture("finished", 1, status = CaptureStatus.READY)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return result!!.getOrThrow()
    }
}
