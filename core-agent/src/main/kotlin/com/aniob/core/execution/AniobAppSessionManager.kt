package com.aniob.core.execution

import com.aniob.core.domain.AniobScreenState

/**
 * Manages active application sessions and browser tabs.
 * Reuses existing foreground/warm sessions rather than triggering cold starts (saving 1-2s).
 * BrowserContext-style reuse: sessions are reused within a 5-minute TTL, after which
 * a warm session is treated as stale and a cold start is planned.
 * Pure JVM design.
 */
class AniobAppSessionManager {

    data class AppSession(
        val packageName: String,
        val lastForegroundTimestamp: Long = System.currentTimeMillis(),
        val lastAccessTime: Long = System.currentTimeMillis(),
        val lastScreenState: AniobScreenState? = null,
        val activityStack: List<String> = emptyList(),
        val activeTabs: MutableMap<String, String> = mutableMapOf() // urlDomain -> tabId
    ) {
        fun isStale(now: Long = System.currentTimeMillis(), ttlMs: Long = SESSION_TTL_MS): Boolean {
            return now - lastAccessTime > ttlMs
        }
    }

    private val sessions = mutableMapOf<String, AppSession>()
    private var currentForegroundPackage: String? = null

    companion object {
        const val SESSION_TTL_MS: Long = 5 * 60 * 1000L // 5-min reuse window
    }

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
        val now = System.currentTimeMillis()
        if (existing != null) {
            val updatedStack = (existing.activityStack + currentActivity).takeLast(5)
            sessions[packageName] = existing.copy(
                lastForegroundTimestamp = now,
                lastAccessTime = now,
                activityStack = updatedStack
            )
        } else {
            sessions[packageName] = AppSession(
                packageName = packageName,
                activityStack = listOf(currentActivity)
            )
        }
    }

    /**
     * Returns the existing (non-stale) session for [packageName], or creates and registers
     * a fresh one — BrowserContext-style reuse within the 5-minute TTL.
     */
    @Synchronized
    fun getOrCreateSession(packageName: String): AppSession {
        val existing = sessions[packageName]
        if (existing != null && !existing.isStale()) {
            touch(packageName)
            return sessions[packageName]!!
        }
        val fresh = AppSession(packageName = packageName)
        sessions[packageName] = fresh
        return fresh
    }

    @Synchronized
    fun getSession(packageName: String): AppSession? = sessions[packageName]

    /**
     * Records the latest captured screen for a session, refreshing its access time.
     */
    @Synchronized
    fun onSessionScreenUpdated(packageName: String, screenState: AniobScreenState?) {
        val existing = sessions[packageName]
        if (existing != null) {
            sessions[packageName] = existing.copy(
                lastScreenState = screenState,
                lastAccessTime = System.currentTimeMillis()
            )
        } else if (screenState != null) {
            sessions[packageName] = AppSession(
                packageName = packageName,
                lastScreenState = screenState
            )
        }
    }

    private fun touch(packageName: String) {
        val existing = sessions[packageName] ?: return
        sessions[packageName] = existing.copy(lastAccessTime = System.currentTimeMillis())
    }

    @Synchronized
    fun planAppLaunch(targetPackage: String, targetUrlDomain: String? = null): LaunchPlan {
        if (currentForegroundPackage == targetPackage) {
            val matchedTabId = if (targetUrlDomain != null) {
                sessions[targetPackage]?.activeTabs?.get(targetUrlDomain)
            } else null
            return LaunchPlan(
                mode = LaunchMode.ALREADY_FOREGROUND,
                packageName = targetPackage,
                reuseBrowserTabId = matchedTabId,
                estimatedLatencyMs = 0L
            )
        }

        val existingSession = sessions[targetPackage]
        // TTL staleness check: a session older than 5 minutes is treated as cold.
        if (existingSession != null && !existingSession.isStale()) {
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
        touch(packageName)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
        currentForegroundPackage = null
    }
}
