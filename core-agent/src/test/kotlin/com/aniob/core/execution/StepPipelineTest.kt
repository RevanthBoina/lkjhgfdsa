package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.TaskContext
import com.aniob.core.learning.LearningPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the single shared [StepPipeline] (finding #1 + honest completion).
 * Every seam is a fake, so these run on the JVM with no device and no LLM.
 */
class StepPipelineTest {

    private fun node(id: Int, text: String, clickable: Boolean = true) = AniobNode(
        id = id,
        className = "android.widget.Button",
        text = text,
        viewId = "res/$id",
        bounds = AniobRect(0, id * 100, 200, id * 100 + 80),
        isClickable = clickable
    )

    private fun screen(pkg: String, vararg texts: String) = AniobScreenState(
        packageName = pkg,
        activityName = "$pkg.Main",
        nodes = texts.mapIndexed { i, t -> node(i + 1, t) },
        treeHash = "hash-$pkg-${texts.joinToString("|")}"
    )

    private fun tap(text: String) = AniobAction.Tap(SemanticTarget.Text(text))

    private val ctx = TaskContext(
        instruction = "Open display settings",
        successCriteria = SuccessCriteria(mustContainText = listOf("Display"))
    )

    private fun pipeline(
        learning: LearningPipeline = LearningPipeline.NoOp,
        records: MutableList<StepPipeline.StepRecord> = mutableListOf()
    ) = StepPipeline(learning = learning, recordStep = { records += it })

    @Test
    fun `happy path dispatch and verify reports continue`() {
        val before = screen("com.a", "Settings")
        val after = screen("com.a", "Display")
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = before,
            proposal = StepPipeline.Proposal.Model(tap("Settings"), "LOCAL"),
            capture = { before },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = after) }
        )
        assertEquals(StepResult.TerminalState.CONTINUE, result.terminalState)
        assertTrue(result.verification!!.isExpected)
        assertEquals(1, result.nextCtx.trajectory.size)
        assertEquals("LOCAL", result.nextCtx.condensedHistory.last().substringBefore(":"))
    }

    @Test
    fun `finish without evidence keeps working instead of succeeding`() {
        val screenNow = screen("com.a", "Settings")
        // Criteria demand "Display", the screen shows only "Settings" -> deterministic reject.
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = screenNow,
            proposal = StepPipeline.Proposal.Model(AniobAction.Finish("done"), "MOCK"),
            capture = { screenNow },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = screenNow) }
        )
        assertEquals(StepResult.TerminalState.CONTINUE, result.terminalState)
        assertEquals(1, result.nextCtx.rejectedFinishCount)
        assertFalse(result.verification!!.isExpected)
    }

    @Test
    fun `finish with evidence succeeds`() {
        val screenNow = screen("com.a", "Display", "Brightness")
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = screenNow,
            proposal = StepPipeline.Proposal.Model(AniobAction.Finish("done"), "MOCK"),
            capture = { screenNow },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = screenNow) }
        )
        assertEquals(StepResult.TerminalState.SUCCESS, result.terminalState)
        assertTrue(result.verification!!.isExpected)
    }

    @Test
    fun `three rejected finishes fail the task`() {
        val screenNow = screen("com.a", "Settings")
        val pipe = pipeline()
        var c = ctx
        repeat(2) {
            val r = pipe.executeStep(
                c, screenNow, StepPipeline.Proposal.Model(AniobAction.Finish("x"), "MOCK"),
                capture = { screenNow },
                dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = screenNow) }
            )
            assertEquals(StepResult.TerminalState.CONTINUE, r.terminalState)
            c = r.nextCtx
        }
        val third = pipe.executeStep(
            c, screenNow, StepPipeline.Proposal.Model(AniobAction.Finish("x"), "MOCK"),
            capture = { screenNow },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = screenNow) }
        )
        assertEquals(StepResult.TerminalState.FAIL, third.terminalState)
    }

    @Test
    fun `blocked action fails fast and never dispatches`() {
        var dispatched = false
        val paymentScreen = screen("com.a", "Checkout")
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = paymentScreen,
            proposal = StepPipeline.Proposal.Model(tap("Checkout"), "CLOUD"),
            capture = { paymentScreen },
            dispatch = { dispatched = true; StepPipeline.DispatchOutcome(true) }
        )
        assertFalse("blocked actions must not reach the device", dispatched)
        assertEquals(StepResult.TerminalState.FAIL, result.terminalState)
    }

    @Test
    fun `declined confirmation fails without dispatch`() {
        var dispatched = false
        val sendScreen = screen("com.a", "Send")
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = sendScreen,
            proposal = StepPipeline.Proposal.Model(
                AniobAction.ConfirmWithUser(message = "Send message to unknown contact?"),
                "CLOUD"
            ),
            capture = { sendScreen },
            dispatch = { dispatched = true; StepPipeline.DispatchOutcome(true) },
            confirm = { false }
        )
        assertFalse(dispatched)
        assertEquals(StepResult.TerminalState.FAIL, result.terminalState)
    }

    @Test
    fun `safety sees the resolved node not just the action string`() {
        // The sensitive-data rule only fires when the *resolved* node is an editable field.
        // Without node-awareness this input would slip through with isEditable=null.
        val editableNode = AniobNode(
            id = 1,
            className = "android.widget.EditText",
            text = "Verification code",
            bounds = AniobRect(0, 100, 200, 180),
            isEditable = true
        )
        val formScreen = AniobScreenState(
            packageName = "com.a",
            activityName = "com.a.Main",
            nodes = listOf(editableNode),
            treeHash = "hash-form"
        )
        val result = pipeline().executeStep(
            ctx = ctx,
            planningScreen = formScreen,
            proposal = StepPipeline.Proposal.Model(
                AniobAction.InputText(SemanticTarget.SomIndex(1), "123456"),
                "CLOUD"
            ),
            capture = { formScreen },
            dispatch = { StepPipeline.DispatchOutcome(true) }
        )
        assertEquals(StepResult.TerminalState.FAIL, result.terminalState)
        assertNotNull("the editable node must have been resolved for the gate", result.resolvedNode)
    }

    @Test
    fun `verified steps reach learning and successes persist trajectory`() {
        val learned = RecordingLearning()
        val screenNow = screen("com.a", "Display")
        pipeline(learning = learned).executeStep(
            ctx = ctx,
            planningScreen = screenNow,
            proposal = StepPipeline.Proposal.FastPath(AniobAction.Finish("done")),
            capture = { screenNow },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = screenNow) }
        )
        assertEquals(1, learned.verifiedSteps)
        assertEquals(1, learned.taskSuccesses)
        assertEquals(1, learned.lastTrajectory.size)
    }

    @Test
    fun `failed steps never reach the learning pipeline`() {
        val learned = RecordingLearning()
        val before = screen("com.a", "Settings")
        val after = screen("com.a", "Settings") // unchanged -> no-effect
        pipeline(learning = learned).executeStep(
            ctx = ctx,
            planningScreen = before,
            proposal = StepPipeline.Proposal.Model(tap("Settings"), "LOCAL"),
            capture = { before },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = after) }
        )
        assertEquals(0, learned.verifiedSteps)
        assertEquals(0, learned.taskSuccesses)
    }

    @Test
    fun `every step is recorded exactly once`() {
        val records = mutableListOf<StepPipeline.StepRecord>()
        val before = screen("com.a", "Settings")
        val after = screen("com.a", "Display")
        pipeline(records = records).executeStep(
            ctx = ctx,
            planningScreen = before,
            proposal = StepPipeline.Proposal.Skill(tap("Settings")),
            capture = { before },
            dispatch = { StepPipeline.DispatchOutcome(true, screenAfter = after) }
        )
        assertEquals(1, records.size)
        assertEquals("SKILL", records.first().provider)
        assertTrue(records.first().verified)
    }

    private inner class RecordingLearning : LearningPipeline {
        var verifiedSteps = 0
        var taskSuccesses = 0
        var lastTrajectory: List<AniobAction> = emptyList()

        override fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState) {
            verifiedSteps++
        }

        override fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>) {
            taskSuccesses++
            lastTrajectory = trajectory
        }
    }
}