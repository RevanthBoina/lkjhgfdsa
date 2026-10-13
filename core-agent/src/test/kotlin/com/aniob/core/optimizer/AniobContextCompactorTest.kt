package com.aniob.core.optimizer

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobContextCompactorTest {

    @Test
    fun testShortTrajectoryNotCompacted() {
        val compactor = AniobContextCompactor(maxUncompactedTurns = 3)
        compactor.addTurn(
            AgentTrajectoryTurn(
                stepIndex = 0,
                screenSummary = "HomeScreen",
                action = AniobAction.Tap(SemanticTarget.Text("Settings"))
            )
        )
        compactor.addTurn(
            AgentTrajectoryTurn(
                stepIndex = 1,
                screenSummary = "SettingsScreen",
                action = AniobAction.Tap(SemanticTarget.Text("Network"))
            )
        )

        val prompt = compactor.buildCompactedContext("You are a helpful agent.", "Open Network Settings")
        assertTrue(prompt.contains("HISTORY:"))
        assertTrue(prompt.contains("Step 1: tap"))
        assertTrue(prompt.contains("Step 2: tap"))
        assertTrue(!prompt.contains("PRIOR_STEPS_SUMMARY:"))
    }

    @Test
    fun testLongTrajectorySlidingWindowCompaction() {
        val compactor = AniobContextCompactor(maxUncompactedTurns = 2)
        // Add 5 steps
        for (i in 0 until 5) {
            compactor.addTurn(
                AgentTrajectoryTurn(
                    stepIndex = i,
                    screenSummary = "Screen_$i",
                    action = AniobAction.Tap(SemanticTarget.SomIndex(i + 1)),
                    observationOutcome = "Navigated to Screen_${i + 1}"
                )
            )
        }

        val prompt = compactor.buildCompactedContext("System", "Goal")
        assertTrue(prompt.contains("PRIOR_STEPS_SUMMARY:"))
        assertTrue(prompt.contains("Compacted 3 prior steps"))
        assertTrue(prompt.contains("RECENT_STEPS:"))
        // Recent turns (steps 4 and 5) must be present in recent steps
        assertTrue(prompt.contains("Step 4:"))
        assertTrue(prompt.contains("Step 5:"))
    }
}
