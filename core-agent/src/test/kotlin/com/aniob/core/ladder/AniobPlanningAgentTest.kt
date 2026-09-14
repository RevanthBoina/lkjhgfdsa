package com.aniob.core.ladder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobPlanningAgentTest {

    private val agent = AniobPlanningAgent()

    @Test
    fun `first update starts pending with user instruction and empty completed`() {
        val progress = agent.updateProgress(
            userInstruction = "Open settings",
            previousOperation = null,
            previousProgress = null,
            focusContent = null
        )

        assertTrue(progress.completed.isEmpty())
        assertEquals(listOf("Open settings"), progress.pending)
        assertEquals(
            "Task: Open settings | Completed 0:  | Pending: Open settings",
            progress.summary
        )
    }

    @Test
    fun `subsequent update appends previous operation and continues`() {
        val first = agent.updateProgress("Open settings", null, null, null)

        val second = agent.updateProgress(
            userInstruction = "Open settings",
            previousOperation = "Step 0 executed",
            previousProgress = first,
            focusContent = "settings screen"
        )

        assertEquals(listOf("Step 0 executed"), second.completed)
        assertEquals(
            listOf("Continue: Open settings", "Use focus: settings screen"),
            second.pending
        )
        assertTrue(second.summary.contains("Completed 1: Step 0 executed"))
        assertTrue(second.summary.contains("Pending: Continue: Open settings, Use focus: settings screen"))
        assertTrue(second.summary.startsWith("Task: Open settings |"))
    }

    @Test
    fun `completed stops growing once it reaches ten entries`() {
        var progress: TaskProgress? = null
        for (i in 0 until 12) {
            progress = agent.updateProgress(
                userInstruction = "Task",
                previousOperation = "Op $i",
                previousProgress = progress,
                focusContent = null
            )
        }

        // Guard is `size < 10`: ops 0..9 are added, ops 10..11 are refused → hard cap of 10.
        assertEquals("Completed list must stay at 10", 10, progress!!.completed.size)
        assertTrue(progress!!.completed.contains("Op 9"))
        assertFalse(progress!!.completed.contains("Op 10"))
        // Summary shows only the newest 3 for the completed section
        assertTrue(progress!!.summary.contains("Completed 10: Op 7, Op 8, Op 9"))
        assertFalse(progress!!.summary.contains("Op 11"))
    }

    @Test
    fun `focus content omitted when null`() {
        val first = agent.updateProgress("Task", "Step 0 executed", null, null)
        val second = agent.updateProgress("Task", null, first, null)

        assertEquals(listOf("Continue: Task"), second.pending)
        assertFalse(second.pending.any { it.startsWith("Use focus") })
    }

    @Test
    fun `blank previous operation is not added to completed`() {
        val progress = agent.updateProgress("Task", "   ", null, null)
        assertTrue(progress.completed.isEmpty())
    }
}