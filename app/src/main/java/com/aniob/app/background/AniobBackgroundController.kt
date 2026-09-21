package com.aniob.app.background

import android.app.Activity
import android.content.Context
import com.aniob.app.overlay.AniobOverlayPill
import java.lang.ref.WeakReference

/**
 * Manages background automation integrity:
 * - On task start: minimizes Aniob (`moveTaskToBack(true)`) so the Accessibility Service
 *   perceives the target application rather than Aniob's own chat UI (no self-observation loop).
 * - Starts the foreground service to protect process priority.
 * - On task completion: notification-only. The old return-to-front activity start was removed
 *   (UX-6) because background activity starts are restricted since Android 10 and usually fail
 *   silently; the in-app result card renders on next open via the persisted summary.
 */
object AniobBackgroundController {

    private var currentActivityRef: WeakReference<Activity>? = null
    private var pill: AniobOverlayPill? = null

    fun registerActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    fun unregisterActivity(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }

    fun onTaskStarted(context: Context, prompt: String) {
        AniobForegroundService.startService(context, prompt)

        // Status pill 2.0 (UX-3): narration + pause/stop while the user watches.
        pill = AniobOverlayPill(
            context = context,
            onCancelTask = { AniobForegroundService.requestStop() },
            onPauseToggle = { AniobForegroundService.requestPauseToggle() },
            onOpenApp = { AniobForegroundService.requestOpenApp(context) }
        ).also { it.show(0, "Getting ready") }

        currentActivityRef?.get()?.let { activity ->
            activity.runOnUiThread {
                activity.moveTaskToBack(true)
            }
        }
    }

    /** Narration line for the pill + notification; called from the ViewModel's record path. */
    fun onStepNarrated(stepIndex: Int, narration: String) {
        pill?.updateStep(stepIndex, narration)
        AniobForegroundService.updateProgress(stepIndex, narration)
    }

    fun onTaskPaused(paused: Boolean) {
        pill?.setPaused(paused)
    }

    fun onTaskFinished(context: Context, summary: String) {
        pill?.showDoneAndDismiss()
        pill = null
        // Quiet Return: notification-only completion. No background activity start (UX-6).
        AniobForegroundService.finishWithNotification(context, summary)
    }

    /** A11y was revoked mid-run: drop the pill and post the recovery door (UX-6). */
    fun onAccessibilityLost(context: Context) {
        pill?.hide()
        pill = null
        AniobForegroundService.notifyAccessibilityLost(context)
    }
}

