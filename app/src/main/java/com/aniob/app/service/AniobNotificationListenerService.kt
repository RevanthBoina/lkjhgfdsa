package com.aniob.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reflex 4: Notification monitor (macroDroid trigger provider).
 * Watches for OTP / password / pin notifications and blocks them from entering the
 * LLM context window by never forwarding the content anywhere (safety interceptor).
 * This is a zero-LLM reflex.
 */
class AniobNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val text = sbn.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                .orEmpty()
            val title = sbn.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
            val combined = "$title $text"
            if (SENSITIVE_PATTERNS.any { combined.contains(it, ignoreCase = true) }) {
                // Blocklist match: never surface notification content to any prompt.
                Log.i(TAG, "Blocked sensitive notification from ${sbn.packageName} (OTP/password reflex)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notification inspect failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    companion object {
        private const val TAG = "AniobNotifListener"
        private val SENSITIVE_PATTERNS = listOf("otp", "one-time", "verification code", "password", "pin")
    }
}