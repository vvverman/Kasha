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

    @Test fun contentFailureKeepsExistingMessage() {
        assertEquals("actionFailed", pollFailureMessageKey(ApplicationPollFailure.CONTENT))
    }
}
