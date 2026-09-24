package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AniobReplayEngineTest {

    private fun screen(packageName: String, text: String): AniobScreenState {
        val node = AniobNode(
            id = 1,
            className = "android.widget.TextView",
            text = text,
            isClickable = true,
            isEnabled = true,
            isVisibleToUser = true,
            bounds = AniobRect(0, 0, 100, 50)
        )
        return AniobScreenState(
            packageName = packageName,
            nodes = listOf(node)
        )
    }

    @Test
    fun `step 0 requires fingerprint match and does not bypass`() {
        val engine = AniobReplayEngine()
        val expectedScreen = screen("com.example.app", "Settings")
        val expectedFp = AniobFingerprint.computeScreenFingerprint(expectedScreen)

        val trajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = "open settings",
            packageName = "com.example.app",
            steps = listOf(
                AniobReplayEngine.ReplayStep(expectedFp, AniobAction.Tap(SemanticTarget.Text("Settings")))
            )
        )
        engine.registerTrajectory(trajectory)

        // Same screen -> succeeds
        val actionMatch = engine.nextAction(trajectory, stepIndex = 0, currentScreen = expectedScreen)
        assertNotNull(actionMatch)
        assertEquals(trajectory.steps[0].action, actionMatch)

        // Different screen on step 0 -> must return null (no bypass!)
        val divergedScreen = screen("com.example.app", "Different Screen")
        val actionDiverged = engine.nextAction(trajectory, stepIndex = 0, currentScreen = divergedScreen)
        assertNull("Step 0 must reject diverged screen fingerprint", actionDiverged)
    }

    @Test
    fun `rejects mismatched package name`() {
        val engine = AniobReplayEngine()
        val scr = screen("com.other.app", "Settings")
        val fp = AniobFingerprint.computeScreenFingerprint(scr)

        val trajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = "open settings",
            packageName = "com.example.app",
            steps = listOf(
                AniobReplayEngine.ReplayStep(fp, AniobAction.Tap(SemanticTarget.Text("Settings")))
            )
        )

        val action = engine.nextAction(trajectory, stepIndex = 0, currentScreen = scr)
        assertNull("Must reject when screen package does not match trajectory package", action)
    }

    @Test
    fun `registers and finds trajectory`() {
        val engine = AniobReplayEngine()
        val trajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = "test task",
            packageName = "com.test",
            steps = emptyList()
        )
        engine.registerTrajectory(trajectory)
        assertEquals(trajectory, engine.findTrajectory("test task"))

        engine.removeTrajectory("test task")
        assertNull(engine.findTrajectory("test task"))
    }
}
