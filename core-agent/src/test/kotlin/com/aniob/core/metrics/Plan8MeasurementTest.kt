package com.aniob.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Plan8MeasurementTest {

    private fun run(
        ok: Boolean = true,
        steps: Int = 10,
        ms: Long = 20_000,
        falseSuccess: Boolean = false,
        qBefore: Int = 0,
        underAsk: Boolean = false,
        id: String = "t",
        idx: Int = 0
    ) = TaskMetrics(
        succeeded = ok, steps = steps, wallClockMs = ms, inferenceMs = ms / 2,
        falseSuccess = falseSuccess, inconclusiveVerdicts = 0, degradationsAnnounced = 0,
        questionsBeforeStarting = qBefore, questionsTotal = qBefore,
        failedForLackOfQuestion = underAsk, tokensUsed = 1000, cachedPrefixBytes = 500,
        taskId = id, runIndex = idx
    )

    // ---------------------------------------------- 8.5 the twelve

    @Test
    fun `a false success is not a success`() {
        assertFalse(run(ok = true, falseSuccess = true).trustworthySuccess)
        assertTrue(run(ok = true, falseSuccess = false).trustworthySuccess)
    }

    @Test
    fun `there are exactly twelve metrics and each has a display name`() {
        assertEquals(12, TaskMetrics.DISPLAY_ORDER.size)
        val keys = run().asMap().keys
        TaskMetrics.DISPLAY_ORDER.forEach { (k, name) ->
            assertTrue("$k is displayed but never emitted", keys.contains(k))
            assertTrue("$k has no human name", name.isNotBlank())
        }
    }

    @Test
    fun `tokens are a real number, not the hardcoded zero`() {
        assertEquals(1000, run().asMap()["tokens_used"])
    }

    // ---------------------------------------------- 8.4 variance

    @Test
    fun `a single run cannot be a measurement`() {
        val r = VarianceReport.report("t", listOf(run()))
        assertTrue(r.underpowered)
        assertTrue(r.summaryLine().contains("UNDERPOWERED"))
    }

    @Test
    fun `ten runs are reported as median and spread`() {
        val runs = (1..10).map { run(steps = it + 5, idx = it) }
        val r = VarianceReport.report("t", runs)
        assertFalse(r.underpowered)
        assertEquals(10, r.runs)
        assertEquals(10.5, r.steps.median, 0.001)
        assertEquals(6.0, r.steps.min, 0.001)
        assertEquals(15.0, r.steps.max, 0.001)
        assertTrue(r.steps.format().contains("p25-p75"))
    }

    @Test
    fun `claimed success and trustworthy success are reported separately`() {
        val runs = (1..10).map { run(ok = true, falseSuccess = it <= 3, idx = it) }
        val r = VarianceReport.report("t", runs)
        assertEquals(1.0, r.successRate, 0.001)
        assertEquals(0.7, r.trustworthySuccessRate, 0.001)
        assertEquals(3, r.falseSuccessCount)
        assertTrue(r.summaryLine().contains("false success"))
    }

    @Test
    fun `a small difference over ten runs is not distinguishable from noise`() {
        val baseline = VarianceReport.report("t", (1..10).map { run(ok = it <= 6, idx = it) })
        val variant = VarianceReport.report("t", (1..10).map { run(ok = it <= 7, idx = it) })
        val c = VarianceReport.compare(baseline, variant)
        assertTrue("60% -> 70% on n=10 must not be called a win", c is VarianceReport.Comparison.NotDistinguishable)
        assertTrue((c as VarianceReport.Comparison.NotDistinguishable)
            .explanation.contains("do not report it as a win"))
    }

    @Test
    fun `a large real improvement is called a win`() {
        val baseline = VarianceReport.report("t", (1..10).map { run(ok = it <= 1, idx = it) })
        val variant = VarianceReport.report("t", (1..10).map { run(ok = true, idx = it) })
        assertTrue(VarianceReport.compare(baseline, variant) is VarianceReport.Comparison.Better)
    }

    @Test
    fun `a large regression is called a regression`() {
        val baseline = VarianceReport.report("t", (1..10).map { run(ok = true, idx = it) })
        val variant = VarianceReport.report("t", (1..10).map { run(ok = it <= 1, idx = it) })
        assertTrue(VarianceReport.compare(baseline, variant) is VarianceReport.Comparison.Worse)
    }

    @Test
    fun `identical runs are not proof of equality`() {
        val a = VarianceReport.report("t", (1..10).map { run(idx = it) })
        val b = VarianceReport.report("t", (1..10).map { run(idx = it) })
        val c = VarianceReport.compare(a, b)
        assertTrue(c is VarianceReport.Comparison.NotDistinguishable)
        assertTrue((c as VarianceReport.Comparison.NotDistinguishable)
            .explanation.contains("not the same as proven equal"))
    }

    @Test
    fun `the suite report leads with false successes`() {
        val clean = VarianceReport.report("clean", (1..10).map { run(idx = it) })
        val dirty = VarianceReport.report("dirty", (1..10).map { run(falseSuccess = it <= 2, idx = it) })
        val out = VarianceReport.suiteReport(listOf(clean, dirty))
        assertTrue(out.contains("False successes: 2"))
        assertTrue(out.contains("Fix these before reading anything else"))
        assertTrue(VarianceReport.suiteReport(listOf(clean)).contains("False successes: 0."))
    }

    @Test
    fun `under-asking is surfaced in the report`() {
        val r = VarianceReport.report("t", (1..10).map { run(underAsk = it <= 4, idx = it) })
        assertEquals(4, r.underAskFailures)
        assertTrue(r.summaryLine().contains("guessed instead of asking"))
    }

    @Test
    fun `questions before starting is tracked as a first-class number`() {
        val r = VarianceReport.report("t", (1..10).map { run(qBefore = if (it <= 5) 1 else 0, idx = it) })
        assertEquals(0.5, r.questionsBeforeStarting.median, 0.001)
        assertEquals(1.0, r.questionsBeforeStarting.max, 0.001)
    }

    @Test
    fun `reporting on zero runs is refused rather than shown as zero`() {
        val threw = try { VarianceReport.report("t", emptyList()); false } catch (e: IllegalArgumentException) { true }
        assertTrue(threw)
    }

    @Test
    fun `the underpowered suite says so out loud`() {
        val r = VarianceReport.report("t", (1..3).map { run(idx = it) })
        assertTrue(VarianceReport.suiteReport(listOf(r)).contains("treat these numbers as anecdotes"))
    }
}
