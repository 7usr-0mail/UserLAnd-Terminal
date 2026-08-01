package tech.ula

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import tech.ula.R

/** Keeps lengthy filesystem/download work visible and foreground-prioritized. */
class ProcessingForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                ensureChannel()
                startForeground(NOTIFICATION_ID, notification(title))
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Terminal processing",
                    NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows active filesystem and download operations"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
        }
    }

    private fun notification(title: String) = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_icon)
            .setContentTitle(title)
            .setContentText("Terminal is working in the background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))
            .build()

    companion object {
        private const val ACTION_UPDATE = "tech.terminal.ula.processing.UPDATE"
        private const val ACTION_STOP = "tech.terminal.ula.processing.STOP"
        private const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "terminal_processing"
        private const val NOTIFICATION_ID = 1001

        fun update(context: Context, title: String) {
            val intent = Intent(context, ProcessingForegroundService::class.java)
                    .setAction(ACTION_UPDATE).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProcessingForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
