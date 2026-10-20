package com.aniob.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time-to-first-action accounting. A fake clock keeps every assertion exact: the point of the
 * class is the arithmetic between marks, so a sleep in a test would only make it flaky.
 */
class StartupTimelineTest {

    @Test
    fun marksAreMillisecondsFromTheTaskStart() {
        var now = 1_000L
        val timeline = StartupTimeline { now }
        timeline.mark(StartupTimeline.FIRST_MARK)
        now += 120
        timeline.mark(StartupTimeline.MARK_BACKGROUNDED)
        now += 70
        timeline.mark(StartupTimeline.MARK_CAPTURE)
        now += 400
        timeline.mark(StartupTimeline.MARK_PROMPT)
        now += 430
        timeline.mark(StartupTimeline.MARK_TTFT)
        now += 50
        timeline.mark(StartupTimeline.MARK_ACTION)
        now += 60
        timeline.mark(StartupTimeline.MARK_DISPATCHED)

        assertEquals(0L, timeline.since(StartupTimeline.FIRST_MARK))
        assertEquals(120L, timeline.since(StartupTimeline.MARK_BACKGROUNDED))
        assertEquals(190L, timeline.since(StartupTimeline.MARK_CAPTURE))
        assertEquals(590L, timeline.since(StartupTimeline.MARK_PROMPT))
        assertEquals(1_020L, timeline.since(StartupTimeline.MARK_TTFT))
        assertEquals(1_130L, timeline.since(StartupTimeline.MARK_DISPATCHED))
        assertTrue(timeline.hasFirstAction())
    }

    @Test
    fun aMarkIsRecordedOnceSoARetryCannotDistortTheTimeline() {
        var now = 0L
        val timeline = StartupTimeline { now }
        timeline.mark(StartupTimeline.FIRST_MARK)
        now = 10
        timeline.mark(StartupTimeline.MARK_PROMPT)
        now = 900
        timeline.mark(StartupTimeline.MARK_PROMPT)
        assertEquals(10L, timeline.since(StartupTimeline.MARK_PROMPT))
    }

    @Test
    fun withoutATaskStartNothingIsMeasured() {
        val timeline = StartupTimeline { 5L }
        timeline.mark(StartupTimeline.MARK_CAPTURE)
        assertNull(timeline.since(StartupTimeline.MARK_CAPTURE))
        assertTrue(timeline.deltas().isEmpty())
        assertFalse(timeline.hasFirstAction())
    }

    @Test
    fun theLogLineIsParseableAndEndsWithTheTotal() {
        var now = 0L
        val timeline = StartupTimeline { now }
        timeline.mark(StartupTimeline.FIRST_MARK)
        now = 100
        timeline.mark(StartupTimeline.MARK_CAPTURE)
        now = 250
        timeline.mark(StartupTimeline.MARK_DISPATCHED)

        assertEquals(
            "STARTUP_LATENCY{task_start=0,capture=100,dispatched=250,total_ms=250}",
            timeline.logLine()
        )
    }

    @Test
    fun aPendingFirstActionStillReportsTheLatestMark() {
        var now = 0L
        val timeline = StartupTimeline { now }
        timeline.mark(StartupTimeline.FIRST_MARK)
        now = 40
        timeline.mark(StartupTimeline.MARK_TTFT)

        assertFalse(timeline.hasFirstAction())
        assertTrue(timeline.logLine().endsWith(",total_ms=40}"))
    }
}
