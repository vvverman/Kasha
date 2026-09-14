package brain.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureTransportPresentationTest {
    @Test fun idleHasNoEmptyPlayerOnAnyTab() {
        for (home in listOf(false, true)) assertEquals(CaptureTransportPresentation.HIDDEN,
            captureTransportPresentation(home, false, false, false, false, false))
    }
    @Test fun homeRecordingHasOneExpandedTransport() {
        assertEquals(CaptureTransportPresentation.EXPANDED_RECORDING,
            captureTransportPresentation(true, true, false, false, false, false))
    }
    @Test fun recordingInOtherScreenKeepsCompactTransport() {
        assertEquals(CaptureTransportPresentation.COMPACT,
            captureTransportPresentation(false, true, false, false, false, false))
    }
    @Test fun playbackResultRecoveryAndStartingRemainVisible() {
        for (state in 0..3) assertEquals(CaptureTransportPresentation.COMPACT,
            captureTransportPresentation(true, false, state == 0, state == 1, state == 2, state == 3))
    }
    @Test fun finalizingDoesNotDuplicateHomeWaveform() {
        assertEquals(CaptureTransportPresentation.EXPANDED_RECORDING,
            captureTransportPresentation(true, true, true, false, true, true))
    }
}
