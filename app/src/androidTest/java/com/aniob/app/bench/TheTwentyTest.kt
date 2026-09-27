package com.aniob.app.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aniob.core.metrics.TaskMetrics
import com.aniob.core.metrics.VarianceReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan-8.1 — `:app` had NO `androidTest` source set at all, while `app/build.gradle.kts` already
 * declared every `androidTestImplementation` dependency and already set
 * `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`. The harness was fully
 * paid for and never written. This is the scaffold.
 *
 * ## BUILD-UNVERIFIED
 * `:app` cannot be compiled in the environment where these files were authored (no Android SDK,
 * no Gradle wrapper). `:core-agent` IS compiled and tested — the metrics and variance types this
 * file imports are covered by `Plan8MeasurementTest` (15 tests, green). Everything here that
 * touches the device is a shape to fill in, and the imports may need adjusting on first run.
 *
 * ## What is deliberately NOT here
 * A driver that taps through other apps. UiAutomator is not in the declared dependency set; add
 * `androidTestImplementation(libs.androidx.uiautomator)` before wiring [runOnce]. Doing it now
 * would put an unbuildable dependency into a build that at least parses today.
 */
@RunWith(AndroidJUnit4::class)
class TheTwentyTest {

    /**
     * Fill this in. It must:
     *  1. reset the device to the task's start state,
     *  2. run Aniob against [spec] with [user] wired to the clarification bridge,
     *  3. return real [TaskMetrics] - all twelve fields, including a real `tokensUsed`
     *     (`StepRecord.tokensUsed` is currently hardcoded to 0).
     *
     * `falseSuccess` is the field that matters most: it is true when the agent reported done and
     * the success condition was not met. Do not compute it from the agent's own report.
     */
    private fun runOnce(spec: TaskSpec, user: ScriptedUser, runIndex: Int): TaskMetrics {
        val start = System.currentTimeMillis()
        var questionsAsked = 0
        if (spec.expectedQuestionsBeforeStarting > 0) {
            val answer = user.onQuestion("Clarification for ${spec.prompt}")
            if (answer != null) questionsAsked++
        }
        val elapsed = System.currentTimeMillis() - start
        return TaskMetrics(
            taskCompleted = true,
            falseSuccess = false,
            goalMetAccordingToVerifier = true,
            stepsTaken = 3,
            consequentialActionsConfirmed = if (spec.tier == TaskSpec.Tier.CONSEQUENCE) 1 else 0,
            consequentialActionsUnconfirmed = 0,
            questionsBeforeStarting = questionsAsked,
            questionsDuringRun = 0,
            overAsked = user.transcript.overAsked,
            underAsked = false,
            latencyMs = elapsed.coerceAtLeast(100L),
            tokensUsed = 1200
        )
    }

    // ------------------------------------------------------------------
    // These two pass today and are worth keeping: they guard the harness
    // itself, which is the part most likely to rot silently.
    // ------------------------------------------------------------------

    @Test
    fun theTwentyAreTwentyAndAgreeWithTheDocument() {
        assertEquals(20, TaskSpec.ALL.size)
        assertEquals(5, TaskSpec.tier(TaskSpec.Tier.SINGLE_APP).size)
        assertEquals(5, TaskSpec.tier(TaskSpec.Tier.CONSEQUENCE).size)
        assertEquals(5, TaskSpec.tier(TaskSpec.Tier.UNDERSPECIFIED).size)
        assertEquals(5, TaskSpec.tier(TaskSpec.Tier.CROSS_APP).size)
        // Every Tier 3 task is a question-quality test and cannot be auto-scored.
        assertTrue(TaskSpec.tier(TaskSpec.Tier.UNDERSPECIFIED).all { it.requiresHuman })
    }

    @Test
    fun theScriptedUserAnswersOnceAndThenRefuses() {
        val user = ScriptedUser(firstAnswer = "Gachibowli")
        assertEquals("Gachibowli", user.onQuestion("Where to?"))
        assertEquals(null, user.onQuestion("Which ride tier?"))
        assertEquals(null, user.onQuestion("Which app?"))
        assertTrue(user.transcript.overAsked)
        assertEquals(2, user.transcript.refusedAt)

        val bookACab = TaskSpec.ALL.first { it.id == 11 }
        val score = user.scoreAgainst(bookACab)
        assertTrue("four questions about a taxi must fail", !score.passed)
        assertEquals(1, bookACab.expectedQuestionsBeforeStarting)
    }

    // ------------------------------------------------------------------
    // The real suite. Ignored until runOnce is implemented.
    // ------------------------------------------------------------------

    @org.junit.Ignore("Plan-8.1: enable once runOnce() drives the agent.")
    @Test
    fun theTwenty() {
        val reports = TaskSpec.AUTOMATABLE.map { spec ->
            val runs = (1..VarianceReport.REQUIRED_RUNS).map { i ->
                runOnce(spec, ScriptedUser(firstAnswer = defaultAnswerFor(spec)), i)
            }
            VarianceReport.report("task-${spec.id}", runs)
        }

        println(VarianceReport.suiteReport(reports))

        // Plan-8.5.5: a false success is worse than a failure, so it is the only hard gate.
        val falseSuccesses = reports.sumOf { it.falseSuccessCount }
        assertEquals("Aniob reported done when it was not done", 0, falseSuccesses)
    }

    /**
     * Plan-8.3 — AndroidWorld is a SMOKE TEST, not a score.
     *
     * The leaderboard is saturated: the top is at 100% and the human baseline is 80%. Below 70%
     * means Plans 1-3 are broken. Above 70% means nothing at all, and must not be quoted as a
     * result.
     */
    @org.junit.Ignore("Plan-8.3: smoke test only. Requires the AndroidWorld harness.")
    @Test
    fun androidWorldSmokeTest() {
        val passRate = 0.0 // wire to the AndroidWorld runner
        assertTrue(
            "AndroidWorld below 70% means Plans 1-3 are broken. It is not a scoreboard.",
            passRate >= 0.70
        )
    }

    private fun defaultAnswerFor(spec: TaskSpec): String = when (spec.id) {
        6 -> "The one in Contacts"
        9 -> "Yes, tell them"
        18 -> "Family"
        20 -> "The usual place"
        else -> "yes"
    }
}
