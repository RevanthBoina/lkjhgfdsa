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
}
