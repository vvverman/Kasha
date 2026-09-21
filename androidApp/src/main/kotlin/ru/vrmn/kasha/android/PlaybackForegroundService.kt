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
 * Системная оболочка только явно запущенного проигрывания.
 *
 * startForegroundService() асинхронный: stop может прийти раньше onStartCommand().
 * На Android 15 нельзя останавливать ещё не промотированный FGS, иначе система позже
 * выбросит ForegroundServiceDidNotStartInTimeException. Поэтому stop до первого
 * startForeground() фиксируется как желаемое состояние, а сам сервис сначала
 * промотируется и затем немедленно завершает себя.
 */
internal class PlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        serviceCreated = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground()
        foregroundStarted = true
        if (!desiredRunning) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        serviceCreated = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val restart = desiredRunning
        super.onDestroy()
        // start() мог прийти во время teardown уже существующего сервиса.
        if (restart) applicationContext.startForegroundService(Intent(applicationContext, PlaybackForegroundService::class.java))
    }

    private fun showForeground() {
        val copy = AndroidPlatformCopy.forLanguage(resources.configuration.locales[0].language)
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, copy.playback, NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_kasha_notification)
            .setContentTitle("Kasha")
            .setContentText(copy.playback)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "kasha-playback"
        private const val ID = 4102

        @Volatile private var desiredRunning = false
        @Volatile private var serviceCreated = false
        @Volatile private var foregroundStarted = false

        fun start(context: Context) {
            desiredRunning = true
            if (!serviceCreated) {
                context.startForegroundService(Intent(context, PlaybackForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            desiredRunning = false
            // Не отменяем ещё не промотированный startForegroundService().
            // onStartCommand() обязан сначала вызвать startForeground(), затем stopSelf().
            if (foregroundStarted) {
                context.stopService(Intent(context, PlaybackForegroundService::class.java))
            }
        }
    }
}
