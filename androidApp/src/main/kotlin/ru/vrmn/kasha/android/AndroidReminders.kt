package ru.vrmn.kasha.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import brain.model.Task
import brain.studio.DevicePermissionKind
import brain.studio.DevicePermissionState
import brain.studio.ReminderGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.TimeZone

/** Одна системная точка пробуждения; моменты повторов и атомарный claim всегда считает Core. */
internal class AndroidReminders(
    context: Context,
    private val repository: AndroidStudioRepository,
    private val permissions: AndroidPermissions,
) : ReminderGateway {
    private val app = context.applicationContext
    private val alarm = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val gate = Mutex()

    override val available: Boolean get() =
        permissions.status(DevicePermissionKind.NOTIFICATIONS) == DevicePermissionState.GRANTED &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override suspend fun sync(tasks: List<Task>) = withContext(Dispatchers.IO) {
        gate.withLock { syncLocked(tasks) }
    }

    override suspend fun notify(task: Task) = withContext(Dispatchers.IO) {
        gate.withLock { notifyLocked(task) }
    }

    /** BOOT/time-change/foreground вызывают только reconciliation, никогда не запись или permission prompt. */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (available) {
                val due = repository.claimTaskReminders(System.currentTimeMillis(), TimeZone.getDefault().id)
                due.forEach { notifyLocked(it) }
            }
            syncLocked(repository.snapshot().tasks)
        }
    }

    suspend fun claim(now: Long, zoneId: String): List<Task> = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!available) return@withLock emptyList()
            val due = repository.claimTaskReminders(now, zoneId)
            syncLocked(repository.snapshot().tasks)
            due
        }
    }

    /** Сохраняет доменное изменение прежде любых OS-операций. Ошибка уведомлений не отменяет save. */
    suspend fun <T> afterTaskChange(taskId: String? = null, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            val result = action()
            val id = taskId ?: (result as? Task)?.id
            if (id != null) safely { notifications.cancel(id, 0) }
            safely { syncLocked(repository.snapshot().tasks) }
            result
        }
    }

    suspend fun requestForNewReminder() {
        val hasSchedule = withContext(Dispatchers.IO) {
            repository.snapshot().tasks.any { !it.completed && it.nextReminderAt > 0 && it.nextReminderAt < Long.MAX_VALUE }
        }
        if (hasSchedule && permissions.foreground && permissions.status(DevicePermissionKind.NOTIFICATIONS) == DevicePermissionState.NOT_DETERMINED) {
            safely { permissions.request(DevicePermissionKind.NOTIFICATIONS) }
        }
        // Отказ не меняет саму задачу. При последующем разрешении foreground снова сверит очередь.
        safely { reconcile() }
    }

    private suspend fun notifyLocked(task: Task) {
        if (!available) return
        val current = repository.snapshot().tasks.firstOrNull { it.id == task.id } ?: return
        if (current.completed || current.dueAt != task.dueAt || current.updatedAt != task.updatedAt) return
        ensureChannel()
        val open = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.Builder().scheme("kasha").authority("task").appendPath(task.id).build()
            putExtra(EXTRA_TASK_ID, task.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val copy = AndroidPlatformCopy.forLanguage(app.resources.configuration.locales[0].language)
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_kasha_notification)
            .setContentTitle(copy.reminders)
            .setContentText(task.text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(task.text))
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(true).build()
        safely { notifications.notify(task.id, 0, notification) }
    }

    private fun syncLocked(tasks: List<Task>) {
        if (!available) { cancelAlarm(); return }
        val at = AndroidReminderPolicy.nextWakeAt(
            tasks.asSequence().filterNot { it.completed }.map { it.nextReminderAt },
            System.currentTimeMillis(),
        ) ?: run { cancelAlarm(); return }
        val pending = checkNotNull(alarmIntent(create = true))
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        try {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            // Permission мог быть отозван между проверкой и schedule. Никакого обхода доступа.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancelAlarm() {
        alarmIntent(create = false)?.let { alarm.cancel(it); it.cancel() }
    }

    private fun alarmIntent(create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        app, 4103,
        Intent(app, AndroidReminderReceiver::class.java).setAction(ACTION_TICK),
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        val copy = AndroidPlatformCopy.forLanguage(app.resources.configuration.locales[0].language)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, copy.reminders, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private suspend fun safely(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Данные уже сохранены; следующая OS/foreground сверка повторит sync. */ }
    }

    companion object {
        const val EXTRA_TASK_ID = "kasha.taskId"
        const val ACTION_TICK = "ru.vrmn.kasha.REMINDER_TICK"
        private const val CHANNEL = "kasha-task-reminders"
    }
}

/** Receiver не запускает microphone/media FGS и не создаёт продуктовую сессию. */
internal class AndroidReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowed = setOf(
            AndroidReminders.ACTION_TICK, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (intent.action !in allowed) return
        val result = goAsync()
        val runtime = try { (context.applicationContext as KashaApplication).platform }
        catch (_: Exception) { result.finish(); return }
        runtime.scope.launch(Dispatchers.IO) {
            try { withTimeout(8_000) { runtime.reminders.reconcile() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Следующий foreground повторит сверку. */ }
            finally { result.finish() }
        }
    }
}
