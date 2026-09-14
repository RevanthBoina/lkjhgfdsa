package com.aniob.core

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteTarget
import com.aniob.core.tools.DevicePowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAutoRouterTest {

    @Test
    fun testOfflineForcedToLocalSLM() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 90, isNetworkAvailable = false)

        val decision = AniobAutoRouter.decideRoute("Search contacts", screen, power)
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
        assertTrue(decision.reason.contains("offline", ignoreCase = true))
    }

    @Test
    fun testLowBatteryOffloadedToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 14, isCharging = false, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Navigate map", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("battery", ignoreCase = true))
    }

    @Test
    fun testVisionNeedRoutesToOmnirouteCloud() {
        // Screen with >= 5 unlabelled clickable nodes AND vision task wording
        val unlabelledNodes = (1..6).map { id ->
            AniobNode(id = id, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(id * 50, 0, id * 50 + 40, 100))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = unlabelledNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Look at the photo on screen and tap icon", screen, power)
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.requiresVision)
    }

    @Test
    fun testSimpleTextNavRoutesToLocalSLM() {
        val textNodes = (1..10).map { id ->
            AniobNode(id = id, className = "TextView", text = "Item $id", isClickable = true, bounds = AniobRect(0, id * 50, 200, id * 50 + 40))
        }
        val screen = AniobScreenState(packageName = "com.test.app", nodes = textNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Select item 3", screen, power)
        assertEquals(RouteTarget.LOCAL_SLM, decision.target)
    }

    @Test
    fun testMissingModelFileEscalatesToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Search notes",
            screenState = screen,
            powerState = power,
            isModelFileMissing = true
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("missing", ignoreCase = true))
    }

    @Test
    fun testLocalFailureEscalationToCloud() {
        val screen = AniobScreenState(packageName = "com.test.app")
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = "Open settings",
            screenState = screen,
            powerState = power,
            lastLocalFailCount = 2
        )
        assertEquals(RouteTarget.OMNIROUTE_CLOUD, decision.target)
        assertTrue(decision.reason.contains("failed 2 times", ignoreCase = true))
    }
}
