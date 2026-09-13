package com.aniob.app.background

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.aniob.app.MainActivity
import java.lang.ref.WeakReference

/**
 * Manages background automation integrity:
 * - On task start: minimizes Aniob activity (`moveTaskToBack(true)`) so Accessibility Service
 *   perceives the target application rather than Aniob's own chat UI (eliminating self-observation loops).
 * - Starts foreground service to protect process priority.
 * - On task completion: brings Aniob back to foreground with result.
 */
object AniobBackgroundController {

    private var currentActivityRef: WeakReference<Activity>? = null

    fun registerActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    fun unregisterActivity(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }

    fun onTaskStarted(context: Context, prompt: String) {
        // Start Foreground Service
        AniobForegroundService.startService(context, prompt)

        // Minimize Aniob activity so target app is in active window
        currentActivityRef?.get()?.let { activity ->
            activity.runOnUiThread {
                activity.moveTaskToBack(true)
            }
        }
    }

    fun onTaskFinished(context: Context, summary: String) {
        // Stop Foreground Service
        AniobForegroundService.stopService(context)

        // Return to Aniob chatroom
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("task_result_summary", summary)
        }
        context.startActivity(intent)
    }
}
