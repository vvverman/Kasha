package brain.studio

import brain.application.ApplicationPollFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PollFailurePresentationTest {
    @Test fun reminderFailureDoesNotOpenGlobalModal() {
        assertNull(pollFailureMessageKey(ApplicationPollFailure.REMINDERS))
    }

    @Test fun transportFailureKeepsExistingMessage() {
        assertEquals("audioFailed", pollFailureMessageKey(ApplicationPollFailure.TRANSPORT))
    }

    @Test fun contentFailureIdentifiesTheFailedRead() {
        assertEquals("loadFailed", pollFailureMessageKey(ApplicationPollFailure.CONTENT))
    }

    @Test fun startupReadFailureIsNotAnUnrelatedActionError() {
        assertEquals("loadFailed", actionFailureMessageKey("storage unreadable", initialized = false))
        assertEquals("actionFailed", actionFailureMessageKey("unknown", initialized = true))
    }

    @Test fun knownRecoverableReasonsArePreserved() {
        for (initialized in listOf(false, true)) {
            for (key in listOf("saveFailed", "audioFailed", "stopPlayback", "currentExists")) {
                assertEquals(key, actionFailureMessageKey(key, initialized))
            }
        }
    }

    @Test fun readFailureIsLocalizedForEverySupportedLanguage() {
        for (language in Languages.codes) {
            kotlin.test.assertTrue(Copy.text(language, "loadFailed").isNotBlank())
        }
    }
}
