package com.aniob.core.execution

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.execution.AniobAppSessionManager.LaunchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAppSessionManagerTest {

    private fun warmedManager(pkg: String = "com.example.app"): Pair<AniobAppSessionManager, AniobAppSessionManager.AppSession> {
        val manager = AniobAppSessionManager()
        manager.onAppForegrounded(pkg, "MainActivity")
        manager.registerBrowserTab(pkg, "https://example.com", "tab-1")
        return manager to manager.getSession(pkg)!!
    }

    @Test
    fun `getOrCreateSession returns a fresh (non-stale) session within TTL and touches it`() {
        val (manager, before) = warmedManager()
        manager.onAppForegrounded("com.other.app") // move foreground away to isolate session reuse
        val reused = manager.getOrCreateSession("com.example.app")
        assertEquals(before.packageName, reused.packageName)
        assertTrue(
            "Access time is refreshed on reuse",
            reused.lastAccessTime >= before.lastAccessTime
        )
    }

    @Test
    fun `isStale reflects the 5 minute TTL`() {
        val (_, session) = warmedManager()
        assertFalse("Fresh session is warm", session.isStale())
        assertTrue(
            "6 minutes later it is stale",
            session.copy(lastAccessTime = System.currentTimeMillis() - 6 * 60 * 1000L)
                .isStale()
        )
    }

    @Test
    fun `planAppLaunch reuses warm session and cold starts stale ones`() {
        val (manager, _) = warmedManager()
        // The warmed session is for the currently foreground app, so reusing it is "already foreground".
        assertEquals(LaunchMode.ALREADY_FOREGROUND, manager.planAppLaunch("com.example.app").mode)
        assertEquals(
            "tab-1",
            manager.planAppLaunch("com.example.app", "https://example.com").reuseBrowserTabId
        )
        // A package that was never warmed must cold start
        assertEquals(LaunchMode.COLD_START, manager.planAppLaunch("com.other.app").mode)

        // After the foreground moves elsewhere, the warm session is BRING_TO_FRONT.
        manager.onAppForegrounded("com.other.app")
        assertEquals(LaunchMode.BRING_TO_FRONT, manager.planAppLaunch("com.example.app").mode)
    }

    @Test
    fun `planAppLaunch cold starts a warmed but stale session`() {
        val manager = AniobAppSessionManager()
        manager.onAppForegrounded("com.example.app")
        // Bring another package to foreground so the target session is no longer "already foreground".
        manager.onAppForegrounded("com.other.app")
        // Force staleness by mutating the stored session via reflection.
        val sessionField = AniobAppSessionManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionField.get(manager) as MutableMap<String, AniobAppSessionManager.AppSession>
        val staleSession = sessions.getValue("com.example.app")
            .copy(lastAccessTime = System.currentTimeMillis() - AniobAppSessionManager.SESSION_TTL_MS - 1)
        sessions["com.example.app"] = staleSession

        assertEquals(LaunchMode.COLD_START, manager.planAppLaunch("com.example.app").mode)
    }

    @Test
    fun `onSessionScreenUpdated refreshes access time and screen state`() {
        val (manager, sessionBefore) = warmedManager()
        val screen = AniobScreenState(packageName = "com.example.app", treeHash = "newHash")
        manager.onSessionScreenUpdated("com.example.app", screen)

        val session = manager.getSession("com.example.app")!!
        assertEquals("newHash", session.lastScreenState?.treeHash)
        assertTrue(session.lastAccessTime >= sessionBefore.lastAccessTime)
    }

    @Test
    fun `getOrCreateSession on unknown package creates a session`() {
        val manager = AniobAppSessionManager()
        val session = manager.getOrCreateSession("com.brand.new")
        assertEquals("com.brand.new", session.packageName)
    }

    // --- Addition A: session validation before reuse (cache poisoning guard) ---

    private fun screen(pkg: String, hash: String, nodes: Int = 2) = AniobScreenState(
        packageName = pkg,
        treeHash = hash,
        nodes = (1..nodes).map { AniobNode(id = it, className = "Button", text = "b$it") }
    )

    @Test
    fun `validateSessionReuse accepts foreground package with live tree`() {
        val (manager, _) = warmedManager()
        manager.onSessionScreenUpdated("com.example.app", screen("com.example.app", "h1"))
        val result = manager.validateSessionReuse(
            "com.example.app",
            liveForegroundPackage = "com.example.app",
            liveScreenState = screen("com.example.app", "h2")
        )
        assertTrue(result.valid)
    }

    @Test
    fun `validateSessionReuse rejects when OEM killed the app`() {
        val (manager, _) = warmedManager()
        val result = manager.validateSessionReuse(
            "com.example.app",
            liveForegroundPackage = "com.android.launcher",
            liveScreenState = screen("com.android.launcher", "h2")
        )
        assertFalse("Background-killed app must not be reused", result.valid)
        assertTrue(result.reason.contains("not foreground"))
    }

    @Test
    fun `validateSessionReuse rejects empty node tree`() {
        val (manager, _) = warmedManager()
        val result = manager.validateSessionReuse(
            "com.example.app",
            liveForegroundPackage = "com.example.app",
            liveScreenState = screen("com.example.app", "h1", nodes = 0)
        )
        assertFalse(result.valid)
        assertTrue(result.reason.contains("Empty node tree"))
    }

    @Test
    fun `validateSessionReuse rejects stale cached tree hash`() {
        val (manager, _) = warmedManager()
        val staleScreen = screen("com.example.app", "sameHash").copy(
            timestamp = System.currentTimeMillis() - AniobAppSessionManager.TREE_FRESHNESS_MS - 1
        )
        manager.onSessionScreenUpdated("com.example.app", staleScreen)
        val result = manager.validateSessionReuse(
            "com.example.app",
            liveForegroundPackage = "com.example.app",
            liveScreenState = screen("com.example.app", "sameHash")
        )
        assertFalse(result.valid)
        assertTrue(result.reason.contains("stale"))
    }

    @Test
    fun `validateSessionReuse rejects missing session`() {
        val manager = AniobAppSessionManager()
        assertFalse(
            manager.validateSessionReuse("com.unknown", "com.unknown", null).valid
        )
    }

    @Test
    fun `ensureReusableOrInvalidate drops poisoned cache`() {
        val (manager, _) = warmedManager()
        val result = manager.ensureReusableOrInvalidate(
            "com.example.app",
            liveForegroundPackage = "com.android.launcher",
            liveScreenState = null
        )
        assertFalse(result.valid)
        assertSame(
            "Cache entry must be dropped after invalidation",
            null,
            manager.getSession("com.example.app")
        )
        assertEquals(
            "A subsequent launch plan cold starts the invalidated package",
            LaunchMode.COLD_START,
            manager.planAppLaunch("com.example.app").mode
        )
    }
}