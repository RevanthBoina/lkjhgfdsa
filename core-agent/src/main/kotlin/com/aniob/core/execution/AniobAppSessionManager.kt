package com.aniob.core.execution

/**
 * Manages active application sessions and browser tabs.
 * Reuses existing foreground/warm sessions rather than triggering cold starts (saving 1-2s).
 * Pure JVM design.
 */
class AniobAppSessionManager {

    data class AppSession(
        val packageName: String,
        val lastForegroundTimestamp: Long = System.currentTimeMillis(),
        val activityStack: List<String> = emptyList(),
        val activeTabs: MutableMap<String, String> = mutableMapOf() // urlDomain -> tabId
    )

    private val sessions = mutableMapOf<String, AppSession>()
    private var currentForegroundPackage: String? = null

    enum class LaunchMode {
        ALREADY_FOREGROUND, // 0ms delay
        BRING_TO_FRONT,     // warm start (~200ms)
        COLD_START          // cold start (~1500ms)
    }

    data class LaunchPlan(
        val mode: LaunchMode,
        val packageName: String,
        val reuseBrowserTabId: String? = null,
        val estimatedLatencyMs: Long
    )

    @Synchronized
    fun onAppForegrounded(packageName: String, currentActivity: String = "MainActivity") {
        currentForegroundPackage = packageName
        val existing = sessions[packageName]
        if (existing != null) {
            val updatedStack = (existing.activityStack + currentActivity).takeLast(5)
            sessions[packageName] = existing.copy(
                lastForegroundTimestamp = System.currentTimeMillis(),
                activityStack = updatedStack
            )
        } else {
            sessions[packageName] = AppSession(
                packageName = packageName,
                activityStack = listOf(currentActivity)
            )
        }
    }

    @Synchronized
    fun planAppLaunch(targetPackage: String, targetUrlDomain: String? = null): LaunchPlan {
        if (currentForegroundPackage == targetPackage) {
            return LaunchPlan(
                mode = LaunchMode.ALREADY_FOREGROUND,
                packageName = targetPackage,
                estimatedLatencyMs = 0L
            )
        }

        val existingSession = sessions[targetPackage]
        if (existingSession != null) {
            val matchedTabId = if (targetUrlDomain != null) {
                existingSession.activeTabs[targetUrlDomain]
            } else null

            return LaunchPlan(
                mode = LaunchMode.BRING_TO_FRONT,
                packageName = targetPackage,
                reuseBrowserTabId = matchedTabId,
                estimatedLatencyMs = 250L
            )
        }

        return LaunchPlan(
            mode = LaunchMode.COLD_START,
            packageName = targetPackage,
            estimatedLatencyMs = 1500L
        )
    }

    @Synchronized
    fun registerBrowserTab(packageName: String, urlDomain: String, tabId: String) {
        val session = sessions.getOrPut(packageName) { AppSession(packageName = packageName) }
        session.activeTabs[urlDomain] = tabId
    }

    @Synchronized
    fun clear() {
        sessions.clear()
        currentForegroundPackage = null
    }
}
