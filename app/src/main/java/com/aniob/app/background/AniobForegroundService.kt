package com.aniob.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aniob.app.MainActivity
import com.aniob.app.R

/**
 * Foreground service ensuring the OS does not throttle or kill Aniob during
 * multi-step autonomous UI background tasks.
 * Uses Android 14+ FOREGROUND_SERVICE_SPECIAL_USE declaration.
 *
 * UX-6: three real channels (Progress low / Confirmations high / Done default), a progress bar
 * with narration, Pause/Stop actions, and a proper app icon. Completion is Quiet Return — a
 * notification with a deep link, never a background activity start.
 */
class AniobForegroundService : Service() {

    companion object {
        const val CHANNEL_PROGRESS = "aniob_progress"
        const val CHANNEL_CONFIRMATIONS = "aniob_confirmations"
        const val CHANNEL_DONE = "aniob_done"

        private const val NOTIFICATION_ID = 8765
        private const val DONE_NOTIFICATION_ID = 8766
        private const val RECOVERY_NOTIFICATION_ID = 8767

        const val ACTION_START = "com.aniob.app.action.START_TASK"
        const val ACTION_STOP = "com.aniob.app.action.STOP_TASK"
        const val ACTION_PAUSE = "com.aniob.app.action.PAUSE_TASK"
        const val ACTION_CONFIRM_APPROVE = "com.aniob.app.action.CONFIRM_APPROVE"
        const val ACTION_CONFIRM_DENY = "com.aniob.app.action.CONFIRM_DENY"
        const val EXTRA_TASK_PROMPT = "extra_task_prompt"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        private const val CONFIRM_NOTIFICATION_ID = 8768

        /** Lets the ViewModel cancel the running task when the notification Stop action is tapped. */
        @Volatile var onStopRequestedFromNotification: (() -> Unit)? = null

        /** Lets the ViewModel flip its cooperative pause flag from the notification/pill. */
        @Volatile var onPauseToggleFromNotification: (() -> Unit)? = null

        /** UX-4: carries the user's approval decision back into the suspending confirm gate. */
        @Volatile var onConfirmDecision: ((com.aniob.app.ui.model.ConfirmDecision) -> Unit)? = null

        /** Carries the user's approval decision back with task and request IDs to prevent stale approvals. */
        @Volatile var onConfirmDecisionWithIds: ((com.aniob.app.ui.model.ConfirmDecision, String?, String?) -> Unit)? = null

        @Volatile private var currentPrompt: String = ""
        @Volatile private var currentStep: Int = 0
        @Volatile private var currentNarration: String = ""
        @Volatile private var isPaused: Boolean = false

        fun startService(context: Context, taskPrompt: String) {
            currentPrompt = taskPrompt
            currentStep = 0
            currentNarration = "Getting ready"
            isPaused = false
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

        /** Pill/notification Stop action entry point. */
        fun requestStop() {
            onStopRequestedFromNotification?.invoke()
        }

        /** Pill/notification Pause (Resume) action entry point. */
        fun requestPauseToggle() {
            onPauseToggleFromNotification?.invoke()
        }

        /** Pill body tap: open the app on the tracker via the `aniob://tracker` deep link. */
        fun requestOpenApp(context: Context) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse("aniob://tracker")
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } catch (_: Exception) {
                // OEM blocked the start; the user can always open Aniob from the launcher.
            }
        }

        /** Mirrors narration + step progress into the live notification (UX-3 → UX-6). */
        fun updateProgress(stepIndex: Int, narration: String) {
            currentStep = stepIndex
            currentNarration = narration
            instances.forEach { it.refreshNotification() }
        }

        fun setPaused(paused: Boolean) {
            isPaused = paused
            instances.forEach { it.refreshNotification() }
        }

        /** Quiet Return completion: Done-channel notification with a deep link (UX-6). */
        fun finishWithNotification(context: Context, summary: String, isSuccess: Boolean = true) {
            stopService(context)
            if (isSuccess) {
                notifyFinished(context, summary)
            } else {
                notifyFailure(context, summary)
            }
        }

        private fun notifyFinished(context: Context, summary: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://result")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle("✓ Done: $currentPrompt")
                .setContentText(summary)
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(DONE_NOTIFICATION_ID, notification)
        }

        /** Failure variant: amber "Needs attention" with [View] [Retry] (UX-6). */
        fun notifyFailure(context: Context, summary: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val view = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://result")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val viewPi = PendingIntent.getActivity(
                context, 3, view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle("Needs attention: $currentPrompt")
                .setContentText(summary)
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(viewPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, "View", viewPi)
                .build()
            nm.notify(DONE_NOTIFICATION_ID, notification)
        }

        /**
         * UX-4 Seen Confirmations: the approval reaches a minimized user on the high-priority
         * channel, with a full-screen intent that opens the same sheet, plus inline Approve/Deny.
         * A timeout is handled by the ViewModel's gate (60s → DENY), never by silence here.
         */
        fun notifyConfirmation(context: Context, request: com.aniob.app.ui.model.ConfirmRequest) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)

            val fullScreen = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://confirm")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val fullScreenPi = PendingIntent.getActivity(
                context, 7, fullScreen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val approvePi = PendingIntent.getService(
                context, 8,
                Intent(context, AniobForegroundService::class.java).apply {
                    action = ACTION_CONFIRM_APPROVE
                    putExtra(EXTRA_TASK_ID, request.taskId)
                    putExtra(EXTRA_REQUEST_ID, request.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val denyPi = PendingIntent.getService(
                context, 9,
                Intent(context, AniobForegroundService::class.java).apply {
                    action = ACTION_CONFIRM_DENY
                    putExtra(EXTRA_TASK_ID, request.taskId)
                    putExtra(EXTRA_REQUEST_ID, request.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRMATIONS)
                .setContentTitle(request.title)
                .setContentText(request.what)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${request.what}\n${request.why}"))
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(fullScreenPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .addAction(0, "Approve once", approvePi)
                .addAction(0, "Deny", denyPi)
                .build()
            nm.notify(CONFIRM_NOTIFICATION_ID, notification)
        }

        /** Clears the approval door once a decision exists. */
        fun clearConfirmation(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(CONFIRM_NOTIFICATION_ID)
        }

        /** A11y revoked mid-run: recovery door with [Re-enable] [Stop task] (UX-6). */
        fun notifyAccessibilityLost(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val reEnable = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://chat")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val reEnablePi = PendingIntent.getActivity(
                context, 4, reEnable,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(context, AniobForegroundService::class.java).apply { action = ACTION_STOP }
            val stopPi = PendingIntent.getService(
                context, 5, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle("Aniob lost access")
                .setContentText("Re-enable accessibility to continue, or stop the task.")
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(reEnablePi)
                .setAutoCancel(false)
                .addAction(0, "Re-enable", reEnablePi)
                .addAction(0, "Stop task", stopPi)
                .build()
            nm.notify(RECOVERY_NOTIFICATION_ID, notification)
        }

        private val instances = mutableSetOf<AniobForegroundService>()

        internal fun ensureChannels(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live narration while Aniob works"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFIRMATIONS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Risky steps that need your approval"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Completed, failed, or interrupted tasks"
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instances.add(this)
        createNotificationChannels()
    }

    override fun onDestroy() {
        instances.remove(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                onStopRequestedFromNotification?.invoke()
                onStopRequestedFromNotification = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = !isPaused
                onPauseToggleFromNotification?.invoke()
                refreshNotification()
                return START_STICKY
            }
            ACTION_CONFIRM_APPROVE -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                clearConfirmation(this)
                onConfirmDecisionWithIds?.invoke(com.aniob.app.ui.model.ConfirmDecision.APPROVE_ONCE, taskId, reqId)
                    ?: onConfirmDecision?.invoke(com.aniob.app.ui.model.ConfirmDecision.APPROVE_ONCE)
                return START_STICKY
            }
            ACTION_CONFIRM_DENY -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                clearConfirmation(this)
                onConfirmDecisionWithIds?.invoke(com.aniob.app.ui.model.ConfirmDecision.DENY, taskId, reqId)
                    ?: onConfirmDecision?.invoke(com.aniob.app.ui.model.ConfirmDecision.DENY)
                return START_STICKY
            }
            else -> {
                val prompt = intent?.getStringExtra(EXTRA_TASK_PROMPT) ?: currentPrompt
                if (prompt.isNotBlank()) currentPrompt = prompt
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(this, manager)
    }

    internal fun refreshNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // Service not in foreground state; the next startForeground will pick it up.
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("aniob://tracker")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AniobForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(this, AniobForegroundService::class.java).apply { action = ACTION_PAUSE }
        val pausePendingIntent = PendingIntent.getService(
            this, 6, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(currentPrompt.ifBlank { "Aniob is working" })
            .setContentText(currentNarration)
            .setSmallIcon(R.drawable.ic_stat_aniob)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .addAction(0, if (isPaused) "▶ Resume" else "⏸ Pause", pausePendingIntent)
            .addAction(0, "■ Stop", stopPendingIntent)
            .build()
    }
}
