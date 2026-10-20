package com.aniob.core

import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.ResolvedIntentShortcut
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Shortcut resolution, with the *policy* made explicit:
 *
 *  - a shortcut that opens an app must be observable (`requiresObservation = true`) and must name a
 *    package, because an intent that names nothing cannot be verified — that is the defect this
 *    patch removes from "Open Clock";
 *  - a reflex (torch, battery, dial, URL) may finish immediately, because the dispatch *is* the
 *    outcome.
 */
class AniobIntentResolverTest {

    @Before
    fun setUp() {
        AniobAppRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        AniobAppRegistry.resetForTest()
    }

    @Test
    fun testOpenSettingsResolvesToTheSettingsPackage() {
        val result = AniobIntentResolver.resolve("Open Settings")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_MAIN, result?.action)
        assertEquals("com.android.settings", result?.targetPackage)
        assertTrue("opening an app must be observable", result?.requiresObservation == true)
    }

    @Test
    fun testCallNumberIsADialIntent() {
        val result = AniobIntentResolver.resolve("call 9876543210")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_DIAL, result?.action)
        assertEquals("tel:9876543210", result?.uriString)
        assertFalse("a dial intent needs no observation", result?.requiresObservation == true)
    }

    @Test
    fun testUnknownCommandReturnsNull() {
        assertNull(AniobIntentResolver.resolve("Book a cab to downtown"))
        assertNull(AniobIntentResolver.resolve(""))
    }

    @Test
    fun testFlashlightAndBatteryAreReflexes() {
        val torch = AniobIntentResolver.resolve("Turn on flashlight")
        assertEquals(AniobIntentResolver.ACTION_FLASHLIGHT, torch?.action)
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT,
            torch?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
        assertFalse(torch?.requiresObservation == true)

        val battery = AniobIntentResolver.resolve("Check battery")
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            battery?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )

        assertNotNull(AniobIntentResolver.resolve("Flashlight ON"))
        assertNotNull(AniobIntentResolver.resolve("open the flashlight"))
        assertNotNull(AniobIntentResolver.resolve("What is my battery?"))
    }

    @Test
    fun testUrlShortcutsStayValidated() {
        val result = AniobIntentResolver.resolve("open website https://example.com/PathWithCaps?Param=Value123")
        assertEquals(AniobIntentResolver.ACTION_VIEW, result?.action)
        assertEquals("https://example.com/PathWithCaps?Param=Value123", result?.uriString)

        assertNull(AniobIntentResolver.resolve("open website https://user:pass@example.com/secret"))
        assertNull(AniobIntentResolver.resolve("open website https://example.com/\u0000bad"))
        assertNull(AniobIntentResolver.resolve("open website javascript:alert(1)"))
        assertEquals(
            "https://example.com",
            AniobIntentResolver.resolve("browse example.com")?.uriString
        )
    }

    // ------------------------------------------------------------------ //
    // The fix: "Open Clock" is an app launch, not a blind SHOW_ALARMS intent
    // ------------------------------------------------------------------ //

    @Test
    fun testOpenClockNamesAPackageAndRequiresObservation() {
        val clock: ResolvedIntentShortcut? = AniobIntentResolver.resolve("Open Clock")
        assertNotNull(clock)
        // No package was the bug: an intent with targetPackage = null cannot be installed-checked
        // and cannot be observed afterwards.
        assertNotNull("a launch must name the package it expects", clock?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, clock?.action)
        assertEquals(AniobIntentResolver.CATEGORY_LAUNCHER, clock?.category)
        assertTrue("the caller must observe the screen before finishing", clock?.requiresObservation == true)
        assertFalse(
            "clock must not be sent to SHOW_ALARMS: that intent has no observable post-condition",
            clock?.action == "android.intent.action.SHOW_ALARMS"
        )
    }

    @Test
    fun testSetAlarmOpensTheClockAppWhenOneIsKnown() {
        val alarm = AniobIntentResolver.resolve("Set alarm")
        assertNotNull(alarm)
        assertEquals("the clock app is opened so the step can be verified", AniobIntentResolver.ACTION_MAIN, alarm?.action)
        assertNotNull(alarm?.targetPackage)
        assertTrue(alarm?.requiresObservation == true)
    }

    @Test
    fun testAppsResolveFromTheRegistryNotFromATable() {
        val catalog = AniobAppCatalog.getInstance()
        catalog.registerApp(
            com.aniob.core.knowledge.AppCatalogEntry(
                packageName = "com.sample.customapp",
                appName = "Custom App",
                aliases = listOf("sample app")
            )
        )
        val res = AniobIntentResolver.resolve("Open Custom App")
        assertNotNull(res)
        assertEquals("com.sample.customapp", res?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, res?.action)

        // An alias resolves too, and an app nobody installed is not invented.
        assertEquals("com.sample.customapp", AniobIntentResolver.resolve("open sample app")?.targetPackage)
        assertNull(AniobIntentResolver.resolve("Open Zephyr"))
    }

    @Test
    fun testCommonAppsStillResolveOnAColdRegistry() {
        // The seed is a last resort of stock package ids; it exists so a cold cache degrades to
        // "ask the platform", never to "no idea what WhatsApp means".
        assertEquals("com.whatsapp", AniobIntentResolver.resolve("Open WhatsApp")?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, AniobIntentResolver.resolve("launch whatsapp")?.action)
        assertEquals("com.google.android.youtube", AniobIntentResolver.resolve("Open YouTube")?.targetPackage)
        assertEquals("org.telegram.messenger", AniobIntentResolver.resolve("launch telegram")?.targetPackage)
        assertEquals("com.spotify.music", AniobIntentResolver.resolve("Open Spotify")?.targetPackage)
    }

    @Test
    fun testARegisteredLaunchComponentBeatsResolution() {
        AniobAppRegistry.getInstance().hydrate(
            listOf(
                com.aniob.core.knowledge.RegisteredApp(
                    packageName = "com.google.android.deskclock",
                    label = "Clock",
                    launchActivity = "com.google.android.deskclock/com.android.deskclock.DeskClock"
                )
            ),
            mapOf(AniobAppRegistry.ROLE_CLOCK to "com.google.android.deskclock")
        )
        val clock = AniobIntentResolver.resolve("open clock")
        assertEquals("com.google.android.deskclock", clock?.targetPackage)
        assertEquals("com.google.android.deskclock/com.android.deskclock.DeskClock", clock?.launchActivity)
    }

    @Test
    fun testNavigatingToADestinationEscapesInsteadOfValidating() {
        val nav = AniobIntentResolver.resolve("navigate to MG Road, Guntur")
        assertEquals(AniobIntentResolver.ACTION_VIEW, nav?.action)
        assertTrue(nav?.uriString?.startsWith("geo:0,0?q=") == true)
        assertTrue(nav?.uriString?.contains("MG%20Road") == true)
    }
}
