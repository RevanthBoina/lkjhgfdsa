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
        // Create screen with unlabelled clickable nodes (icons without text)
        val unlabelledNodes = listOf(
            AniobNode(id = 1, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(0, 0, 100, 100)),
            AniobNode(id = 2, className = "ImageView", text = "", contentDescription = "", isClickable = true, bounds = AniobRect(100, 0, 200, 100))
        )
        val screen = AniobScreenState(packageName = "com.test.app", nodes = unlabelledNodes)
        val power = DevicePowerState(batteryPercent = 80, isNetworkAvailable = true)

        val decision = AniobAutoRouter.decideRoute("Click profile icon", screen, power)
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
}
