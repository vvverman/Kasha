package brain.ios

import brain.model.*
import brain.studio.AiPackageGateway
import brain.studio.AiPlatformServices
import brain.studio.DeviceCapabilityGateway
import brain.studio.NoopAiPackageGateway
import brain.studio.StudioRepository
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Side-effect adapter вокруг общего repository: Core меняет Task, iOS только
 * синхронизирует системные notifications и публикует platform capabilities.
 */
internal class IosReminderRepository(
    private val delegate: IosRepository,
    private val reminders: IosReminder,
    override val deviceCapabilities: DeviceCapabilityGateway,
    override val cloudAi: IosCloudAiGateway,
) : StudioRepository by delegate, AiPlatformServices {
    override val aiPackages: AiPackageGateway = NoopAiPackageGateway
    override val aiExecution get() = delegate.aiExecution

    suspend fun syncReminders() {
        reminders.sync(delegate.snapshot().tasks)
    }

    suspend fun reconcileReminders() {
        delegate.claimTaskReminders(
            Clock.System.now().toEpochMilliseconds(),
            TimeZone.currentSystemDefault().id,
        )
        syncReminders()
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task =
        delegate.distributeTask(id, request).also { syncReminders() }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task =
        delegate.updateTask(id, update).also { syncReminders() }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task =
        delegate.rescheduleTask(id, update).also {
            reminders.cancel(id)
            syncReminders()
        }

    override suspend fun completeTask(id: String): Task =
        delegate.completeTask(id).also {
            reminders.cancel(id)
            syncReminders()
        }

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
