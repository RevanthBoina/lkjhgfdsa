package com.aniob.core

import com.aniob.core.ladder.AniobIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AniobIntentResolverTest {

    @Test
    fun testOpenSettingsResolvesDirectly() {
        val result = AniobIntentResolver.resolve("Open Settings")
        assertNotNull(result)
        assertEquals("com.android.settings", result?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, result?.action)
    }

    @Test
    fun testCallNumberResolvesToDialIntent() {
        val result = AniobIntentResolver.resolve("call 9876543210")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_DIAL, result?.action)
        assertEquals("tel:9876543210", result?.uriString)
    }

    @Test
    fun testComplexTaskReturnsNullForIntent() {
        val result = AniobIntentResolver.resolve("Book a cab to downtown")
        assertNull("Complex task should not be resolved as single intent shortcut", result)
    }

    @Test
    fun testTurnOnFlashlightResolvesToReflex() {
        val result = AniobIntentResolver.resolve("Turn on flashlight")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_FLASHLIGHT, result?.action)
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT,
            result?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
    }

    @Test
    fun testCheckBatteryResolvesToReflex() {
        val result = AniobIntentResolver.resolve("Check battery")
        assertNotNull(result)
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            result?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
    }

    @Test
    fun testFlashlightVariantsResolveCaseInsensitive() {
        assertNotNull(AniobIntentResolver.resolve("Flashlight ON"))
        assertNotNull(AniobIntentResolver.resolve("open the flashlight"))
        assertNotNull(AniobIntentResolver.resolve("What is my battery?"))
    }

    @Test
    fun testOpenWebsitePreservesCasingAndQuery() {
        val result = AniobIntentResolver.resolve("open website https://example.com/PathWithCaps?Param=Value123")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_VIEW, result?.action)
        assertEquals("https://example.com/PathWithCaps?Param=Value123", result?.uriString)
    }

    @Test
    fun testOpenWebsiteRejectsCredentialsAndControlChars() {
        val credResult = AniobIntentResolver.resolve("open website https://user:pass@example.com/secret")
        assertNull("URLs with embedded credentials must be rejected", credResult)

        val ctrlResult = AniobIntentResolver.resolve("open website https://example.com/\u0000bad")
        assertNull("URLs with control characters must be rejected", ctrlResult)

        val jsResult = AniobIntentResolver.resolve("open website javascript:alert(1)")
        assertNull("JavaScript scheme must be rejected", jsResult)
    }

    @Test
    fun testOpenClockAndAlarmResolveDirectly() {
        val clock = AniobIntentResolver.resolve("Open Clock")
        assertNotNull(clock)
        assertEquals("android.intent.action.SHOW_ALARMS", clock?.action)

        val alarm = AniobIntentResolver.resolve("Set alarm")
        assertNotNull(alarm)
        assertEquals("android.intent.action.SET_ALARM", alarm?.action)

        val spotify = AniobIntentResolver.resolve("Open Spotify")
        assertNotNull(spotify)
        assertEquals("com.spotify.music", spotify?.targetPackage)
    }

    @Test
    fun testOpenWhatsAppAndYouTubeResolveDirectly() {
        val wa1 = AniobIntentResolver.resolve("Open WhatsApp")
        assertNotNull(wa1)
        assertEquals("com.whatsapp", wa1?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, wa1?.action)

        val wa2 = AniobIntentResolver.resolve("launch whatsapp")
        assertNotNull(wa2)
        assertEquals("com.whatsapp", wa2?.targetPackage)

        val yt = AniobIntentResolver.resolve("Open YouTube")
        assertNotNull(yt)
        assertEquals("com.google.android.youtube", yt?.targetPackage)

        val tg = AniobIntentResolver.resolve("launch telegram")
        assertNotNull(tg)
        assertEquals("org.telegram.messenger", tg?.targetPackage)
    }

    @Test
    fun testDynamicAppCatalogResolution() {
        val catalog = com.aniob.core.knowledge.AniobAppCatalog.getInstance()
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
    }
}
