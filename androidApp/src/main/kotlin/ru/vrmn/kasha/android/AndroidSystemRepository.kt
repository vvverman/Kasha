package ru.vrmn.kasha.android

import brain.model.Task
import brain.model.TaskDistributionRequest
import brain.model.TaskScheduleUpdate
import brain.model.TaskUpdate
import brain.studio.AiPlatformServices
import brain.studio.AiExecutionCapabilityGateway
import brain.studio.NoopAiExecutionCapabilityGateway
import brain.studio.NoopAiPackageGateway
import brain.studio.NoopCloudAiGateway
import brain.studio.StudioRepository

/** Подключает системную очередь к уже реализованным Core операциям; не считает повторы самостоятельно. */
internal class AndroidSystemRepository(
    private val delegate: AndroidStudioRepository,
    private val reminders: AndroidReminders,
    override val deviceCapabilities: AndroidPermissions,
    override val aiExecution: AiExecutionCapabilityGateway = NoopAiExecutionCapabilityGateway,
) : StudioRepository by delegate, AiPlatformServices {
    override val aiPackages = NoopAiPackageGateway
    override val cloudAi = NoopCloudAiGateway

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
        val result = reminders.afterTaskChange { delegate.distributeTask(id, request) }
        reminders.requestForNewReminder()
        return result
    }
    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
        val result = reminders.afterTaskChange(id) { delegate.rescheduleTask(id, update) }
        reminders.requestForNewReminder()
        return result
    }
    override suspend fun updateTask(id: String, update: TaskUpdate): Task =
        reminders.afterTaskChange(id) { delegate.updateTask(id, update) }
    override suspend fun completeTask(id: String): Task =
        reminders.afterTaskChange(id) { delegate.completeTask(id) }
    override suspend fun deleteTask(id: String) = reminders.afterTaskChange(id) { delegate.deleteTask(id) }
    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> = reminders.claim(now, zoneId)
}
