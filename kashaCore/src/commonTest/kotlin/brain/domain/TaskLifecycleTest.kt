package brain.domain

import brain.model.ReminderRepeat
import brain.model.Task
import brain.model.TaskScheduleUpdate
import brain.model.TaskUpdate
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskLifecycleTest {
    @Test
    fun completedTaskIsReadOnlyAndCompletionIsIdempotent() {
        val original = Task(
            id = "t",
            text = "Сделать",
            createdAt = 1,
            updatedAt = 1,
            dueAt = 100,
            nextReminderAt = 100,
        )
        val completed = BrainData(tasks = listOf(original)).completeTask("t", 20)
        val task = completed.tasks.single()
        assertEquals(20, task.completedAt)
        assertEquals(20, task.updatedAt)
        assertEquals(0, task.nextReminderAt)

        val repeated = completed.completeTask("t", 999)
        assertEquals(completed, repeated)
        assertFails { repeated.updateTask("t", TaskUpdate("Изменить архив"), 1000) }
        assertFails { repeated.rescheduleTask("t", TaskScheduleUpdate(2000, ReminderRepeat.DAILY), 1000) }
    }

    @Test
    fun dueReminderIsClaimedOnceAndCompletedTaskIsNeverClaimed() {
        val due = 1_700_000_000_000L
        val active = Task(
            id = "active",
            text = "Активная",
            createdAt = 1,
            updatedAt = 1,
            dueAt = due,
            nextReminderAt = due,
            reminderRepeat = ReminderRepeat.THIRTY_MINUTES,
        )
        val archived = active.copy(id = "archived", text = "Архив", completedAt = due - 1)
        val start = BrainData(tasks = listOf(active, archived))

        val (afterFirst, first) = start.claimDueReminders(due, TimeZone.UTC.id)
        assertEquals(listOf("active"), first.map { it.id })
        assertTrue(afterFirst.tasks.first { it.id == "active" }.nextReminderAt > due)

        val (afterSecond, second) = afterFirst.claimDueReminders(due, TimeZone.UTC.id)
        assertTrue(second.isEmpty())
        assertEquals(afterFirst, afterSecond)
    }

    @Test
    fun everyReminderRepeatProducesAFutureOccurrence() {
        val start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilliseconds()
        val zone = TimeZone.UTC
        ReminderRepeat.entries.forEach { repeat ->
            val next = TaskSchedule.nextReminder(start, repeat, zone)
            assertTrue(next > start, "$repeat must move reminder forward")
            val day = Instant.fromEpochMilliseconds(next).toLocalDateTime(zone).dayOfWeek
            if (repeat == ReminderRepeat.WEEKENDS) assertTrue(day in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            if (repeat == ReminderRepeat.WEEKDAYS) assertTrue(day !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        }
    }
}
