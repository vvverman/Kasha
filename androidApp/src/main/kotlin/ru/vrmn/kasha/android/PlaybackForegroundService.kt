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

/** Системная оболочка только явно запущенного проигрывания. После process death не рестартует звук. */
internal class PlaybackForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val copy = AndroidPlatformCopy.forLanguage(resources.configuration.locales[0].language)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, copy.playback, NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 4102, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_kasha_notification)
            .setContentTitle("Kasha")
            .setContentText(copy.playback)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "kasha-playback"
        private const val ID = 4102
        fun start(context: Context) { context.startForegroundService(Intent(context, PlaybackForegroundService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, PlaybackForegroundService::class.java)) }
    }
}
