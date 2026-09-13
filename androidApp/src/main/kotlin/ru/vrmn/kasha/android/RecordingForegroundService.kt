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
    override fun onCreate() {
        super.onCreate()
        serviceActive = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground(paused = desiredPaused)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showForeground(paused: Boolean) {
        val notification = buildNotification(this, paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kasha-active-recording"
        private const val NOTIFICATION_ID = 4101

        @Volatile private var serviceActive = false
        @Volatile private var desiredPaused = false

        /** Вызывать только из foreground/user-visible capture start. */
        fun start(context: Context) {
            desiredPaused = false
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        }

        /** Обновляет уже существующую FGS notification; service повторно не стартует. */
        fun recording(context: Context) = updateNotification(context, paused = false)

        /** Безопасно вызывается из background/system audio callback. */
        fun paused(context: Context) = updateNotification(context, paused = true)

        fun stop(context: Context) {
            desiredPaused = false
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }

        private fun updateNotification(context: Context, paused: Boolean) {
            desiredPaused = paused
            if (!serviceActive) return
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context, paused))
        }

        private fun buildNotification(context: Context, paused: Boolean): Notification {
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kasha_notification)
                .setContentTitle(if (paused) "Kasha · запись на паузе" else "Kasha · идёт запись")
                .setContentText(
                    if (paused) "Откройте Kasha, чтобы продолжить или завершить"
                    else "Микрофон используется для текущей записи",
                )
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build()
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
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
    }
}
