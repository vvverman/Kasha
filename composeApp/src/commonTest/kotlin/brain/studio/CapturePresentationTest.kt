package brain.studio

import kotlin.test.*

class CapturePresentationTest {
    @Test fun interruptedAndFinalizingAreNotPause() {
        assertEquals("recording", recordingStatusKey("recording"))
        assertEquals("paused", recordingStatusKey("paused"))
        assertEquals("captureInterrupted", recordingStatusKey("interrupted"))
        assertEquals("captureFinalizing", recordingStatusKey("finalizing"))
        assertEquals("audioFailed", recordingStatusKey("unknown"))
    }

    @Test fun allSupportedLanguagesHaveCaptureStateLabels() {
        for (language in Languages.codes) for (key in listOf("captureInterrupted", "captureFinalizing", "captureNeedsModel")) {
            assertFalse(KashaCopy.text(language, key).isNullOrBlank(), "$language/$key")
        }
    }
}
