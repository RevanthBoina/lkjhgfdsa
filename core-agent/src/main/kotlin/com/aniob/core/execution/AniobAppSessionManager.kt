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
        const val TREE_FRESHNESS_MS: Long = 30_000L // cached tree older than this is suspect
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

    data class SessionValidation(
        val valid: Boolean,
        val reason: String
    )

    /**
     * Verifies a cached session is still safe to reuse *right now*, rather than trusting the
     * 5-minute TTL alone. An OEM can kill and relaunch an app in the background within that
     * window, after which a reuse would drive a stale tree (wrong app, wrong node ids).
     *
     * @param liveForegroundPackage package actually foregrounded per ActivityManager/UsageStats
     * @param liveScreenState fresh capture, when one is available
     */
    @Synchronized
    fun validateSessionReuse(
        packageName: String,
        liveForegroundPackage: String?,
        liveScreenState: AniobScreenState?,
        now: Long = System.currentTimeMillis()
    ): SessionValidation {
        val session = sessions[packageName]
            ?: return SessionValidation(false, "No cached session for $packageName")

        if (session.isStale(now)) {
            return SessionValidation(false, "Session for $packageName exceeded 5-min TTL")
        }

        if (liveForegroundPackage != packageName) {
            return SessionValidation(
                false,
                "App not foreground anymore (foreground=$liveForegroundPackage, expected=$packageName)"
            )
        }

        if (liveScreenState != null) {
            if (liveScreenState.nodes.isEmpty()) {
                return SessionValidation(false, "Empty node tree for $packageName - surface not ready")
            }
            if (liveScreenState.treeHash.isNotBlank() &&
                liveScreenState.treeHash == session.lastScreenState?.treeHash &&
                now - (session.lastScreenState?.timestamp ?: 0L) > TREE_FRESHNESS_MS
            ) {
                return SessionValidation(false, "Cached tree hash stale (>${TREE_FRESHNESS_MS / 1000}s)")
            }
        }

        return SessionValidation(true, "Session valid for reuse")
    }

    /**
     * Validates and, when invalid, invalidates the cache so the caller can plan a cold start.
     */
    @Synchronized
    fun ensureReusableOrInvalidate(
        packageName: String,
        liveForegroundPackage: String?,
        liveScreenState: AniobScreenState?,
        now: Long = System.currentTimeMillis()
    ): SessionValidation {
        val validation = validateSessionReuse(packageName, liveForegroundPackage, liveScreenState, now)
        if (!validation.valid) {
            sessions.remove(packageName)
            if (currentForegroundPackage == packageName) currentForegroundPackage = null
        }
        return validation
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
