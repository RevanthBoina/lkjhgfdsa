package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FastPath knowledge must survive process death, or "zero-token replay" is a lie. */
class AniobTrajectoryCodecTest {

    private val trajectory = AniobReplayEngine.ReplayTrajectory(
        taskSignature = "open settings",
        packageName = "com.android.settings",
        steps = listOf(
            AniobReplayEngine.ReplayStep("fp0", AniobAction.OpenApp("com.android.settings")),
            AniobReplayEngine.ReplayStep("fp1", AniobAction.Tap(SemanticTarget.Text("Display"))),
            AniobReplayEngine.ReplayStep("fp2", AniobAction.Finish(summary = "opened display"))
        )
    )

    @Test
    fun `round trips a trajectory`() {
        val decoded = AniobTrajectoryCodec.decode(AniobTrajectoryCodec.encode(trajectory))
        assertEquals(1, decoded.size)
        assertEquals("open settings", decoded[0].taskSignature)
        assertEquals(3, decoded[0].steps.size)
        assertEquals("fp1", decoded[0].steps[1].expectedFingerprint)
        assertTrue(decoded[0].steps[1].action is AniobAction.Tap)
    }

    @Test
    fun `a new engine rehydrates the persisted trajectory`() {
        val payload = AniobTrajectoryCodec.encode(trajectory)
        val rehydrated = AniobReplayEngine()
        AniobTrajectoryCodec.decode(payload).forEach { rehydrated.registerTrajectory(it) }
        assertEquals("open settings", rehydrated.findTrajectory("open settings")?.taskSignature)
    }

    @Test
    fun `malformed lines are skipped not fatal`() {
        val payload = AniobTrajectoryCodec.encode(trajectory) + "\nSTEP|only-two-fields\ngarbage\n"
        assertEquals(1, AniobTrajectoryCodec.decode(payload).size)
    }

    @Test
    fun `round trips multiple trajectories`() {
        val second = trajectory.copy(taskSignature = "open wifi", steps = listOf(trajectory.steps[0]))
        val decoded = AniobTrajectoryCodec.decode(
            AniobTrajectoryCodec.encode(listOf(trajectory, second))
        )
        assertEquals(2, decoded.size)
    }

    @Test
    fun `round trips trajectory with pipe and newline characters in taskSignature`() {
        val tricky = trajectory.copy(taskSignature = "search a | b \\ c\nand tap display")
        val decoded = AniobTrajectoryCodec.decode(AniobTrajectoryCodec.encode(tricky))
        assertEquals(1, decoded.size)
        assertEquals("search a | b \\ c\nand tap display", decoded[0].taskSignature)
        assertEquals(3, decoded[0].steps.size)
    }
}