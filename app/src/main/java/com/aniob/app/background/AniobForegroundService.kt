package com.aniob.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aniob.app.MainActivity

/**
 * Foreground service ensuring the OS does not throttle or kill Aniob during
 * multi-step autonomous UI background tasks.
 * Uses Android 14+ FOREGROUND_SERVICE_SPECIAL_USE declaration.
 */
class AniobForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "aniob_foreground"
        private const val NOTIFICATION_ID = 8765
        const val ACTION_START = "com.aniob.app.action.START_TASK"
        const val ACTION_STOP = "com.aniob.app.action.STOP_TASK"
        const val EXTRA_TASK_PROMPT = "extra_task_prompt"

        /** Lets the ViewModel cancel the running task when the notification Stop action is tapped. */
        @Volatile var onStopRequestedFromNotification: (() -> Unit)? = null

        fun startService(context: Context, taskPrompt: String) {
            val intent = Intent(context, AniobForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_PROMPT, taskPrompt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AniobForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            onStopRequestedFromNotification?.invoke()
            onStopRequestedFromNotification = null
            stopSelf()
            return START_NOT_STICKY
        }

        val prompt = intent?.getStringExtra(EXTRA_TASK_PROMPT) ?: "Running autonomous task"
        val notification = buildNotification(prompt)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aniob Foreground",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when Aniob is executing UI tasks in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(taskPrompt: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action identical to the overlay pill's cancel ('■').
        val stopIntent = Intent(this, AniobForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aniob Agent Active")
            .setContentText(taskPrompt)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "■ Stop", stopPendingIntent)
            .build()
    }
}
