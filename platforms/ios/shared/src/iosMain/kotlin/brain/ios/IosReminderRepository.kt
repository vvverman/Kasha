package brain.ios

import brain.model.*
import brain.studio.StudioRepository

/**
 * Side-effect adapter вокруг общего repository: Core меняет Task, iOS только
 * синхронизирует системные UNUserNotificationCenter requests.
 */
internal class IosReminderRepository(
    private val delegate: IosRepository,
    private val reminders: IosReminder,
) : StudioRepository by delegate {

    suspend fun syncReminders() {
        reminders.sync(delegate.snapshot().tasks)
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task =
        delegate.distributeTask(id, request).also { syncReminders() }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task =
        delegate.updateTask(id, update).also { syncReminders() }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task =
        delegate.rescheduleTask(id, update).also { syncReminders() }

    override suspend fun completeTask(id: String): Task =
        delegate.completeTask(id).also { syncReminders() }

    override suspend fun deleteTask(id: String) {
        delegate.deleteTask(id)
        reminders.cancel(id)
        syncReminders()
    }

    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> =
        delegate.claimTaskReminders(now, zoneId).also { due ->
            if (due.isNotEmpty()) syncReminders()
        }
}
