package ru.vrmn.kasha.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Android-only системная оболочка для уже начатой пользователем записи.
 * Не владеет Core state и не перезапускает запись после убийства процесса.
 */
internal class RecordingForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSED -> showForeground(paused = true)
            ACTION_RECORDING, null -> showForeground(paused = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showForeground(paused: Boolean) {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Kasha · запись на паузе" else "Kasha · идёт запись")
            .setContentText(if (paused) "Откройте Kasha, чтобы продолжить или завершить" else "Микрофон используется для текущей записи")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Активная запись Kasha",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Показывается, пока Kasha использует микрофон"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "kasha-active-recording"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_RECORDING = "ru.vrmn.kasha.recording.RECORDING"
        private const val ACTION_PAUSED = "ru.vrmn.kasha.recording.PAUSED"

        fun start(context: Context) = send(context, ACTION_RECORDING, foreground = true)
        fun recording(context: Context) = send(context, ACTION_RECORDING)
        fun paused(context: Context) = send(context, ACTION_PAUSED)
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }

        private fun send(context: Context, action: String, foreground: Boolean = false) {
            val intent = Intent(context, RecordingForegroundService::class.java).setAction(action)
            if (foreground) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
