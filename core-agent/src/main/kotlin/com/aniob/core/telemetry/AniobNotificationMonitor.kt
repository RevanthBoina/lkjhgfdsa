package com.aniob.core.telemetry

/**
 * Structured model for system notification events.
 */
data class NotificationItem(
    val id: String,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasAutoReplyAction: Boolean = false
)

/**
 * Telemetry monitor for device notifications and auto-reply capabilities.
 * Pure JVM design.
 */
class AniobNotificationMonitor {

    private val notificationStream = mutableListOf<NotificationItem>()

    @Synchronized
    fun recordNotification(item: NotificationItem) {
        notificationStream.add(item)
        if (notificationStream.size > 50) {
            notificationStream.removeAt(0)
        }
    }

    @Synchronized
    fun getNotifications(filterPackage: String? = null): List<NotificationItem> {
        return if (filterPackage.isNullOrBlank()) {
            ArrayList(notificationStream)
        } else {
            notificationStream.filter { it.packageName.equals(filterPackage, ignoreCase = true) }
        }
    }

    @Synchronized
    fun findAutoReplyCandidate(keywords: List<String>): NotificationItem? {
        return notificationStream.lastOrNull { item ->
            item.hasAutoReplyAction && keywords.any { kw ->
                item.title.contains(kw, ignoreCase = true) || item.content.contains(kw, ignoreCase = true)
            }
        }
    }

    @Synchronized
    fun clear() {
        notificationStream.clear()
    }
}
