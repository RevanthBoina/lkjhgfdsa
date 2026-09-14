package com.aniob.core.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobExecReportTest {

    @Test
    fun `toMetricSnapshot maps counters provider and decision reason`() {
        val report = ExecReport(
            outcome = ExecOutcome.SUCCESS,
            reason = "Task completed",
            screenReads = 4,
            actions = 3,
            escalations = 1,
            elapsedMs = 2500L,
            stateTrace = listOf("h1", "h2", "h3"),
            providerUsed = "OMNIROUTE_CLOUD",
            decisionReason = "first gemma rejected, escalated to cloud"
        )

        val snapshot = report.toMetricSnapshot(taskId = "t1", stepIndex = 5, timestamp = 123L)

        assertEquals("t1", snapshot.taskId)
        assertEquals(5, snapshot.stepIndex)
        assertEquals("OMNIROUTE_CLOUD", snapshot.provider)
        assertEquals("first gemma rejected, escalated to cloud", snapshot.providerDecisionReason)
        assertEquals("first gemma rejected, escalated to cloud", snapshot.lastRoutingReason)
        assertEquals(2500L, snapshot.stepLatencyMs)
        assertEquals(4, snapshot.screenReads)
        assertEquals(3, snapshot.actions)
        assertEquals(1, snapshot.escalations)
        assertEquals(123L, snapshot.timestamp)
        assertFalse("Cloud escalation is not a fast-path hit", snapshot.fastPathHit)
    }

    @Test
    fun `fast path provider reflects on fastpathHit`() {
        val report = ExecReport(
            outcome = ExecOutcome.SUCCESS,
            reason = "replayed",
            providerUsed = "FASTPATH"
        )
        assertTrue(report.toMetricSnapshot(taskId = "t9").fastPathHit)
    }

    @Test
    fun `toMetricSnapshot fastpath hit false for unknown provider`() {
        val report = ExecReport(
            outcome = ExecOutcome.FAILED_VERIFICATION,
            reason = "no effect",
            providerUsed = "NONE"
        )
        val snapshot = report.toMetricSnapshot(taskId = "t2")
        assertFalse(snapshot.fastPathHit)
        assertEquals(0, snapshot.memoryRetrievals)
        assertEquals(0, snapshot.embeddingSearches)
    }
}