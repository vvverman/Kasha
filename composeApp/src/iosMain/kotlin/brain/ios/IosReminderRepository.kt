package brain.ios

import brain.model.*
import brain.studio.AiPackageGateway
import brain.studio.AiPlatformServices
import brain.studio.CloudAiGateway
import brain.studio.DeviceCapabilityGateway
import brain.studio.NoopAiPackageGateway
import brain.studio.NoopCloudAiGateway
import brain.studio.StudioRepository

/**
 * Side-effect adapter вокруг общего repository: Core меняет Task, iOS только
 * синхронизирует системные UNUserNotificationCenter requests и публикует
 * platform capabilities без переноса системной логики в Core.
 */
internal class IosReminderRepository(
    private val delegate: IosRepository,
    private val reminders: IosReminder,
    override val deviceCapabilities: DeviceCapabilityGateway,
) : StudioRepository by delegate, AiPlatformServices {
    override val aiPackages: AiPackageGateway = NoopAiPackageGateway
    override val cloudAi: CloudAiGateway = NoopCloudAiGateway

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
