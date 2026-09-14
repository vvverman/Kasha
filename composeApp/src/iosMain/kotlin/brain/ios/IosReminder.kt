@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.model.Task
import brain.studio.DevicePermissionState
import brain.studio.ReminderGateway
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.*
import kotlin.coroutines.resume
import kotlin.time.Clock

internal class IosReminder : ReminderGateway {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    override val available: Boolean = true

    /** Уведомления заранее ставятся системой через sync; foreground notify не дублирует delivery. */
    override suspend fun notify(task: Task) = Unit

    override suspend fun sync(tasks: List<Task>) {
        center.removeAllPendingNotificationRequests()
        val now = Clock.System.now().toEpochMilliseconds()
        val planned = tasks.mapNotNull { task ->
            IosReminderPlan.delaySeconds(task, now)?.let { delay -> task to delay }
        }
        if (planned.isEmpty()) return
        if (!ensurePermission()) return
        planned.forEach { (task, delay) -> schedule(task, delay) }
    }

    fun cancel(taskId: String) {
        val id = identifier(taskId)
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    private fun schedule(task: Task, delaySeconds: Double) {
        val content = UNMutableNotificationContent().apply {
            setTitle("Kasha · Задача")
            setBody(task.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty())
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delaySeconds, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifier(task.id),
            content = content,
            trigger = trigger,
        )
        center.addNotificationRequest(request) { _ -> }
    }

    private suspend fun ensurePermission(): Boolean = when (notificationPermission()) {
        DevicePermissionState.GRANTED -> true
        DevicePermissionState.DENIED, DevicePermissionState.UNAVAILABLE -> false
        DevicePermissionState.NOT_DETERMINED -> requestPermission()
    }

    private suspend fun notificationPermission(): DevicePermissionState = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) {
                continuation.resume(
                    settings?.let { IosDeviceCapabilities.mapNotificationStatus(it.authorizationStatus) }
                        ?: DevicePermissionState.UNAVAILABLE
                )
            }
        }
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            if (continuation.isActive) continuation.resume(granted && error == null)
        }
    }

    private fun identifier(taskId: String) = "$PREFIX$taskId"

    private companion object {
        const val PREFIX = "kasha-task-"
    }
}

internal object IosReminderPlan {
    fun delaySeconds(task: Task, nowMillis: Long): Double? {
        if (task.completed) return null
        val next = task.nextReminderAt
        if (next <= nowMillis || next <= 0L || next == Long.MAX_VALUE) return null
        return ((next - nowMillis) / 1000.0).coerceAtLeast(1.0)
    }
}
