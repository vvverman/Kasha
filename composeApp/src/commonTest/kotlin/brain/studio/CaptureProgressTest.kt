package brain.studio

import brain.application.KashaApplicationState
import brain.application.KashaTransportState
import brain.application.TransportOperation
import brain.domain.RecorderPhase
import brain.model.AppSnapshot
import brain.model.Capture
import brain.model.CaptureStatus
import kotlin.test.*

class CaptureProgressTest {
    private fun state(status: CaptureStatus? = null, finalized: Boolean = true,
                      operation: TransportOperation? = null, pending: Boolean = false,
                      phase: RecorderPhase = RecorderPhase.IDLE) = KashaApplicationState(
        initialized = true,
        snapshot = AppSnapshot(captures = status?.let {
            listOf(Capture(id = "source", createdAt = 1, status = it, audioFinalized = finalized))
        }.orEmpty()),
        transport = KashaTransportState(operation = operation, hasPending = pending, recorderPhase = phase),
    )

    @Test fun manualRecoveryDoesNotRenderIdleOrReady() {
        for (status in listOf(null, CaptureStatus.READY)) {
            assertEquals("captureRecovering", captureProgressKey(state(status, operation = TransportOperation.RECOVER)))
        }
    }
    @Test fun automaticRecoveryDuringLaunchHasTheSamePresentation() {
        assertEquals("captureRecovering", captureProgressKey(state(operation = TransportOperation.LAUNCH, pending = true)))
    }
    @Test fun finishAndFinalizingAreNotPausedOrReady() {
        assertEquals("captureFinalizing", captureProgressKey(state(CaptureStatus.READY, operation = TransportOperation.FINISH)))
        assertEquals("captureFinalizing", captureProgressKey(state(phase = RecorderPhase.FINALIZING)))
        assertEquals("captureFinalizing", captureProgressKey(state(CaptureStatus.READY, finalized = false)))
    }
    @Test fun startingAndCancellingDoNotRenderIdle() {
        for (operation in listOf(TransportOperation.START, TransportOperation.CANCEL)) {
            assertEquals("preparing", captureProgressKey(state(operation = operation)))
        }
    }
    @Test fun processingLabelsFollowTheActualCapturePhase() {
        assertEquals("transcribing", captureProgressKey(state(CaptureStatus.TRANSCRIBING)))
        assertEquals("compacting", captureProgressKey(state(CaptureStatus.COMPACTING)))
        assertEquals("preparing", captureProgressKey(state(CaptureStatus.POLISHING)))
        assertEquals("preparing", captureProgressKey(state(CaptureStatus.QUEUED)))
    }
    @Test fun errorsKeepExistingTextAndRetryEvenWithUnfinalizedAudio() {
        for (status in listOf(CaptureStatus.FAILED, CaptureStatus.NEEDS_MODEL)) {
            assertNull(captureProgressKey(state(status, finalized = false)))
        }
    }
    @Test fun normalScreensRemainAvailableOutsideProgress() {
        assertNull(captureProgressKey(state()))
        assertNull(captureProgressKey(state(CaptureStatus.READY)))
        assertNull(captureProgressKey(state(phase = RecorderPhase.RECORDING)))
        assertNull(captureProgressKey(state(phase = RecorderPhase.PAUSED)))
        assertNull(captureProgressKey(state(operation = TransportOperation.SEEK)))
    }
    @Test fun recoveryLabelExistsInEverySupportedLanguage() {
        for (language in Languages.codes) assertFalse(KashaCopy.text(language, "captureRecovering").isNullOrBlank(), language)
    }
}
