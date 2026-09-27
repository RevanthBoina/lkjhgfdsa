package com.aniob.core.memory.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Plan4HistoryMemoryTest {

    @Test
    fun testNeutralWordingContractEnforcement() {
        val bad = "I successfully arrived at settings and the agent completed the task."
        val report = NeutralWordingContract.validate(bad)
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.contains("successfully") })
        assertTrue(report.violations.any { it.contains("arrived at") })
        assertTrue(report.violations.any { it.contains("completed") })
        assertTrue(report.violations.any { it.contains("the agent") })

        val good = "The settings screen was displayed. I tapped the search bar."
        val goodReport = NeutralWordingContract.validate(good)
        assertTrue(goodReport.isValid)
    }

    @Test
    fun testAsyncStepSummaryServiceNonBlockingAndFrozen() {
        val service = AsyncStepSummaryService { stepId, detail ->
            "I observed $detail at step $stepId"
        }

        // Before dispatch, returns fallback
        val result1 = service.getSummaryOrFallback("step-1", "Detailed screen elements")
        assertFalse(result1.isReady)
        assertEquals("Detailed screen elements", result1.fallbackDetail)

        // Dispatch and retrieve
        service.dispatchSummary("step-1", "settings page")
        Thread.sleep(100) // Brief async wait
        val result2 = service.getSummaryOrFallback("step-1", "Detailed screen elements")
        assertTrue(result2.isReady)
        assertNotNull(result2.summaryText)

        // Test freeze
        service.freezeStep("step-1")
        assertTrue(service.isFrozen("step-1"))
    }

    @Test
    fun testTwoEdgeScrubberStripsStaleElementsAtDepthOne() {
        val scrubber = TwoEdgeContextScrubber()
        val turn1 = TwoEdgeContextScrubber.HistoricalTurn(
            stepNumber = 1,
            userPrompt = "Find settings",
            rawUiElementList = listOf("[1] Settings Button", "[2] Profile"),
            planRecitation = "Plan: tap settings",
            ephemeralBlocks = listOf("Temporary search box"),
            screenshotRef = "screenshot_1.png",
            visualSummary = "I tapped Settings.",
            tokenEstimate = 500
        )
        val turn2 = TwoEdgeContextScrubber.HistoricalTurn(
            stepNumber = 2,
            userPrompt = "Turn on dark mode",
            rawUiElementList = listOf("[1] Dark mode switch"),
            planRecitation = "Plan: toggle switch",
            ephemeralBlocks = emptyList(),
            screenshotRef = "screenshot_2.png",
            visualSummary = null,
            tokenEstimate = 300
        )

        val scrubbed = scrubber.scrub(listOf(turn1, turn2))
        assertEquals(2, scrubbed.size)

        // Turn 1 is depth 1 -> UI element list, plan, and ephemeral blocks MUST be scrubbed (null)
        assertNull(scrubbed[0].uiElementList)
        assertNull(scrubbed[0].planRecitation)
        assertNull(scrubbed[0].ephemeralBlocks)

        // Turn 2 is depth 0 (live observation) -> UI elements preserved
        assertNotNull(scrubbed[1].uiElementList)
        assertEquals(1, scrubbed[1].uiElementList!!.size)
    }

    @Test
    fun testSearchableHistoryIndex() {
        val index = SearchableHistoryIndex()
        index.indexStep(1, "Navigated to home screen", screenshotPath = "img1.png", tokenCount = 400)
        index.indexStep(2, "Opened settings page and tapped display", screenshotPath = "img2.png", tokenCount = 500)
        index.indexStep(3, "Selected dark theme", screenshotPath = "img3.png", tokenCount = 300)

        val searchResults = index.search(keyword = "settings")
        assertEquals(listOf(2), searchResults)

        val replay = index.replaySteps(listOf(1, 2))
        assertEquals(2, replay.size)
    }
}
