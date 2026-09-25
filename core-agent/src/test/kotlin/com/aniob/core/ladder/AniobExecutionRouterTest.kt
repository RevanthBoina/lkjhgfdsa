package com.aniob.core.ladder

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.DevicePowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobExecutionRouterTest {

    private val screen = AniobScreenState(
        packageName = "com.android.settings",
        activityName = "SettingsActivity",
        treeHash = "settings-hash-1"
    )
    private val power = DevicePowerState()

    @Test
    fun `model dispatch carries condensed progress summary when planning agent present`() {
        val router = AniobExecutionRouter(planningAgent = AniobPlanningAgent())

        // stepIndex 4 avoids intent/fastpath/skill matches → falls through to ModelDispatch
        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        val dispatch = result as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch
        assertNotNull("Summary must be produced by the planning agent", dispatch.progressSummary)
        assertTrue(dispatch.progressSummary!!.contains("Task: Open wifi settings"))
        assertTrue(dispatch.progressSummary!!.contains("Completed 1"))
    }

    @Test
    fun `router works with nullable planning agent`() {
        val router = AniobExecutionRouter(planningAgent = null)

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        val dispatch = result as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch
        assertNull("No summary when planning agent is disabled", dispatch.progressSummary)
    }

    @Test
    fun `previous progress accumulates across consecutive model dispatches`() {
        val router = AniobExecutionRouter(planningAgent = AniobPlanningAgent())

        val first = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        ) as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch

        // Feed a prior TaskProgress with 1 completed op; the router should append its own
        // "Step 4 executed" operation → completed grows to 2.
        val second = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 5,
            taskSignature = "open wifi settings",
            powerState = power,
            previousProgress = TaskProgress(
                completed = listOf("Step 3 executed"),
                pending = listOf("Continue: Open wifi settings"),
                summary = first.progressSummary.orEmpty()
            )
        ) as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch

        assertEquals("Task: Open wifi settings", second.progressSummary?.substringBefore(" |"))
        assertEquals(
            "Completed count must accumulate: 1 prior + 1 new = 2",
            2,
            second.progressSummary!!.substringAfter("Completed ").substringBefore(":").toInt()
        )
        assertTrue(second.progressSummary!!.contains("Step 3 executed"))
        assertTrue(second.progressSummary!!.contains("Step 4 executed"))
    }

    @Test
    fun `disabled fast path must not attempt cache lookup`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.fastPathEnabled = false

        // Negative control: when fastPathEnabled is false, lookup must immediately return null
        val lookupResult = router.lookup("any task signature")
        assertNull("Disabled fast path must skip cache lookup entirely", lookupResult)

        // When planStep runs with fastPathEnabled = false, it must never yield FastPathStep
        val plan = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )
        assertTrue("Must not return FastPathStep when fastPathEnabled = false", plan !is AniobExecutionRouter.ExecutionPlanResult.FastPathStep)
    }
}