package brain.ios

import brain.domain.RecorderIssueKind
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosRecorderLifecycleTest {
    private fun recording() = IosRecorderLifecycleContext(
        state = RecorderSessionState(
            phase = RecorderPhase.RECORDING,
            activeSessionId = "session.m4a",
        )
    )

    @Test
    fun interruptionNeverAutoResumesRecorder() {
        val began = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.InterruptionBegan(wasSuspended = false),
            recorderActuallyRecording = true,
            recorderPresent = true,
        )

        assertTrue(began.pauseRecorder)
        assertTrue(began.context.systemInterruptionActive)
        assertEquals(RecorderPhase.INTERRUPTED, began.context.state.phase)
        assertEquals(RecorderIssueKind.INTERRUPTION, began.context.state.issue?.kind)
        assertFalse(began.context.state.issue?.recoverable ?: true)

        val ended = IosRecorderLifecycle.reduce(
            current = began.context,
            event = IosAudioSystemEvent.InterruptionEnded(
                canResume = true,
                inputAvailable = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(ended.pauseRecorder)
        assertFalse(ended.context.systemInterruptionActive)
        assertEquals(RecorderPhase.INTERRUPTED, ended.context.state.phase)
        assertTrue(ended.context.state.issue?.recoverable == true)
    }

    @Test
    fun interruptionRecommendationCanKeepExplicitResumeDisabled() {
        val began = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.InterruptionBegan(wasSuspended = false),
            recorderActuallyRecording = true,
            recorderPresent = true,
        )
        val ended = IosRecorderLifecycle.reduce(
            current = began.context,
            event = IosAudioSystemEvent.InterruptionEnded(
                canResume = false,
                inputAvailable = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertEquals(RecorderPhase.INTERRUPTED, ended.context.state.phase)
        assertFalse(ended.context.state.issue?.recoverable ?: true)
    }

    @Test
    fun airPodsLossPausesAndWaitsForExternalInputInsteadOfBuiltinMic() {
        val lost = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.RouteChanged(
                reason = "oldDeviceUnavailable",
                inputAvailable = true,
                previousHadExternalInput = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = true,
            recorderPresent = true,
        )

        assertTrue(lost.pauseRecorder)
        assertTrue(lost.context.waitingForExternalInput)
        assertEquals(RecorderPhase.INTERRUPTED, lost.context.state.phase)
        assertEquals(RecorderIssueKind.INPUT_UNAVAILABLE, lost.context.state.issue?.kind)
        assertFalse(lost.context.state.issue?.recoverable ?: true)

        val builtinOnly = IosRecorderLifecycle.reduce(
            current = lost.context,
            event = IosAudioSystemEvent.ApplicationDidBecomeActive(
                inputAvailable = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertTrue(builtinOnly.context.waitingForExternalInput)
        assertFalse(builtinOnly.context.state.issue?.recoverable ?: true)

        val externalReturned = IosRecorderLifecycle.reduce(
            current = builtinOnly.context,
            event = IosAudioSystemEvent.RouteChanged(
                reason = "newDeviceAvailable",
                inputAvailable = true,
                previousHadExternalInput = false,
                currentHasExternalInput = true,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(externalReturned.context.waitingForExternalInput)
        assertEquals(RecorderPhase.INTERRUPTED, externalReturned.context.state.phase)
        assertTrue(externalReturned.context.state.issue?.recoverable == true)
    }

    @Test
    fun airPodsLossDuringCallBlocksResumeUntilExternalInputReturns() {
        val began = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.InterruptionBegan(wasSuspended = false),
            recorderActuallyRecording = true,
            recorderPresent = true,
        )
        val lostDuringCall = IosRecorderLifecycle.reduce(
            current = began.context,
            event = IosAudioSystemEvent.RouteChanged(
                reason = "oldDeviceUnavailable",
                inputAvailable = true,
                previousHadExternalInput = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertEquals(RecorderIssueKind.INTERRUPTION, lostDuringCall.context.state.issue?.kind)
        assertTrue(lostDuringCall.context.systemInterruptionActive)
        assertTrue(lostDuringCall.context.waitingForExternalInput)

        val callEnded = IosRecorderLifecycle.reduce(
            current = lostDuringCall.context,
            event = IosAudioSystemEvent.InterruptionEnded(
                canResume = true,
                inputAvailable = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(callEnded.context.systemInterruptionActive)
        assertEquals(RecorderPhase.INTERRUPTED, callEnded.context.state.phase)
        assertEquals(RecorderIssueKind.INPUT_UNAVAILABLE, callEnded.context.state.issue?.kind)
        assertFalse(callEnded.context.state.issue?.recoverable ?: true)
        assertTrue(callEnded.context.waitingForExternalInput)

        val returned = IosRecorderLifecycle.reduce(
            current = callEnded.context,
            event = IosAudioSystemEvent.RouteChanged(
                reason = "newDeviceAvailable",
                inputAvailable = true,
                previousHadExternalInput = false,
                currentHasExternalInput = true,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(returned.context.waitingForExternalInput)
        assertEquals(RecorderPhase.INTERRUPTED, returned.context.state.phase)
        assertEquals(RecorderIssueKind.INPUT_UNAVAILABLE, returned.context.state.issue?.kind)
        assertTrue(returned.context.state.issue?.recoverable == true)
    }

    @Test
    fun foregroundReconcilesLostRecorderWithoutRestartingIt() {
        val reconciled = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.ApplicationDidBecomeActive(
                inputAvailable = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(reconciled.pauseRecorder)
        assertEquals(RecorderPhase.INTERRUPTED, reconciled.context.state.phase)
        assertEquals(RecorderIssueKind.SESSION_LOST, reconciled.context.state.issue?.kind)
        assertTrue(reconciled.context.state.issue?.recoverable == true)
    }

    @Test
    fun routeAndForegroundEventsCannotOverrideActiveSystemInterruption() {
        val began = IosRecorderLifecycle.reduce(
            current = recording(),
            event = IosAudioSystemEvent.InterruptionBegan(wasSuspended = false),
            recorderActuallyRecording = true,
            recorderPresent = true,
        )

        val route = IosRecorderLifecycle.reduce(
            current = began.context,
            event = IosAudioSystemEvent.RouteChanged(
                reason = "oldDeviceUnavailable",
                inputAvailable = false,
                previousHadExternalInput = true,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )
        val foreground = IosRecorderLifecycle.reduce(
            current = route.context,
            event = IosAudioSystemEvent.ApplicationDidBecomeActive(
                inputAvailable = true,
                currentHasExternalInput = true,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertEquals(RecorderIssueKind.INTERRUPTION, foreground.context.state.issue?.kind)
        assertTrue(foreground.context.systemInterruptionActive)
        assertTrue(foreground.context.waitingForExternalInput)
        assertFalse(foreground.context.state.issue?.recoverable ?: true)
    }

    @Test
    fun routeLossWhileUserPausedDoesNotPretendRecorderWasRecording() {
        val paused = IosRecorderLifecycleContext(
            state = RecorderSessionState(
                phase = RecorderPhase.PAUSED,
                activeSessionId = "session.m4a",
            )
        )

        val lost = IosRecorderLifecycle.reduce(
            current = paused,
            event = IosAudioSystemEvent.RouteChanged(
                reason = "noSuitableRouteForCategory",
                inputAvailable = false,
                previousHadExternalInput = false,
                currentHasExternalInput = false,
            ),
            recorderActuallyRecording = false,
            recorderPresent = true,
        )

        assertFalse(lost.pauseRecorder)
        assertEquals(RecorderPhase.PAUSED, lost.context.state.phase)
        assertEquals(RecorderIssueKind.INPUT_UNAVAILABLE, lost.context.state.issue?.kind)
        assertFalse(lost.context.state.issue?.recoverable ?: true)
    }
}
