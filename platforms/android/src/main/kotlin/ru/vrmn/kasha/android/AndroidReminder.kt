package ru.vrmn.kasha.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import brain.model.Task
import brain.studio.ReminderGateway

internal class AndroidReminder(private val context: Context) : ReminderGateway {
    private val manager = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Задачи Kasha", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override val available: Boolean
        get() = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun notify(task: Task) {
        if (!available) return
        show(context, task.id.hashCode(), task.text)
    }

    companion object {
        const val CHANNEL_ID = "kasha.tasks"
        fun show(context: Context, id: Int, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Задачи Kasha", NotificationManager.IMPORTANCE_DEFAULT))
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Kasha · Задача")
                .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty())
                .setAutoCancel(true)
                .build()
            manager.notify(id, notification)
        }
    }
}

class AndroidReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidReminder.show(
            context,
            intent.getIntExtra("id", 0),
            intent.getStringExtra("text").orEmpty(),
        )
    }
}
