package brain.ios

import brain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosReminderPlanTest {
    @Test
    fun futureActiveTaskGetsExactOneShotDelay() {
        val now = 1_000_000L
        assertEquals(60.0, IosReminderPlan.delaySeconds(task(now + 60_000L), now))
    }

    @Test
    fun veryNearFutureStillUsesValidMinimumDelay() {
        val now = 1_000_000L
        assertEquals(1.0, IosReminderPlan.delaySeconds(task(now + 250L), now))
    }

    @Test
    fun dueOrPastTaskIsNotRescheduledBeforeCoreClaim() {
        val now = 1_000_000L
        assertNull(IosReminderPlan.delaySeconds(task(now), now))
        assertNull(IosReminderPlan.delaySeconds(task(now - 1L), now))
    }

    @Test
    fun completedAndUnscheduledTasksAreIgnored() {
        val now = 1_000_000L
        assertNull(IosReminderPlan.delaySeconds(task(now + 60_000L, completed = true), now))
        assertNull(IosReminderPlan.delaySeconds(task(Long.MAX_VALUE), now))
        assertNull(IosReminderPlan.delaySeconds(task(0L), now))
    }

    private fun task(next: Long, completed: Boolean = false) = Task(
        id = "task",
        text = "Reminder",
        createdAt = 1L,
        updatedAt = 1L,
        dueAt = next,
        nextReminderAt = next,
        completedAt = if (completed) 2L else null,
    )
}
