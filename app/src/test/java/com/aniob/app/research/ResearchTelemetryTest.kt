package com.aniob.app.research

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the three research metrics: CSV fidelity, per-mode running averages, the HUD line format and
 * the manual override. The clock is injected, so latency assertions are exact and sleep-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ResearchTelemetryTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    // ---------------------------------------------------------------------------------------------
    // Pure: CSV + summary
    // ---------------------------------------------------------------------------------------------

    @Test
    fun csvRoundTripPreservesCommasQuotesAndNewlines() {
        val run = ResearchRun(
            timestampMs = 1_700_000_000_000,
            mode = AblationMode.MOTIF_AND_FAST_A11Y,
            goal = "Send \"hello, Ana\"\nto the group",
            latencySeconds = 12.345, // CSV keeps millisecond precision
            llmCalls = 4,
            localInferences = 2,
            steps = 7,
            success = true,
            failureReason = "",
            intentShortcutUsed = true,
            rememberCacheHit = false
        )
        val parsed = ResearchRun.fromCsvRow(run.toCsvRow())
        requireNotNull(parsed)
        // The newline is collapsed so one task stays one physical row; everything else is exact.
        assertEquals("Send \"hello, Ana\" to the group", parsed.goal)
        assertEquals(run.copy(goal = parsed.goal), parsed)
    }

    @Test
    fun headerAndMalformedRowsAreIgnored() {
        assertNull(ResearchRun.fromCsvRow(ResearchRun.HEADER))
        assertNull(ResearchRun.fromCsvRow(""))
        assertNull(ResearchRun.fromCsvRow("1000,BASELINE,only,three"))
        assertNull(ResearchRun.fromCsvRow("not-a-number,BASELINE,goal,1.000,0,0,1,true,,false,false,false"))
    }

    @Test
    fun summaryComputesRunningAveragesPerMode() {
        val runs = listOf(
            run(mode = AblationMode.BASELINE, latency = 10.0, calls = 2, success = true),
            run(mode = AblationMode.BASELINE, latency = 20.0, calls = 4, success = false),
            run(mode = AblationMode.FULL_HUMAN_SPEED, latency = 4.0, calls = 0, success = true)
        )
        val rows = ResearchSummaryTable.build(runs)
        assertEquals(
            listOf(AblationMode.BASELINE, AblationMode.FULL_HUMAN_SPEED),
            rows.map { it.mode }
        )
        val baseline = rows.first()
        assertEquals(2, baseline.runs)
        assertEquals(1, baseline.successes)
        assertEquals(15.0, baseline.meanLatencySeconds, 0.0001)
        assertEquals(3.0, baseline.meanLlmCallsPerTask, 0.0001)
        assertEquals(50.0, baseline.successRatePercent, 0.0001)

        val full = rows.last()
        assertEquals(100.0, full.successRatePercent, 0.0001)
        assertEquals(0.0, full.meanLlmCallsPerTask, 0.0001)
    }

    @Test
    fun summaryOmitsModesWithoutRunsAndWritesStableCsv() {
        val csv = ResearchSummaryTable.toCsv(
            ResearchSummaryTable.build(listOf(run(AblationMode.INTENT_SHORTCUT_ONLY, 5.0, 1, true)))
        )
        val lines = csv.trim().split("\n")
        assertEquals(ResearchSummaryTable.HEADER, lines[0])
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("INTENT_SHORTCUT_ONLY,1,1,5.00,1.00,100.0"))
    }

    @Test
    fun hudLineMatchesTheRequiredFormat() {
        val row = ResearchSummaryRow(
            mode = AblationMode.BASELINE,
            runs = 3,
            successes = 2,
            meanLatencySeconds = 12.345,
            meanLlmCallsPerTask = 2.5,
            successRatePercent = 66.666
        )
        assertEquals("[BASELINE] Latency: 12.35s | LLM Calls: 2 | Success Rate: 66.7%", row.hudLine())
    }

    @Test
    fun ablationFlagsMatchTheDocumentedTable() {
        assertFalse(AblationMode.BASELINE.intentShortcuts)
        assertFalse(AblationMode.BASELINE.motifBursts)
        assertFalse(AblationMode.BASELINE.fastDeltaVerify)
        assertFalse(AblationMode.BASELINE.fastObservation)
        assertFalse(AblationMode.BASELINE.rememberReplay)

        assertTrue(AblationMode.INTENT_SHORTCUT_ONLY.intentShortcuts)
        assertFalse(AblationMode.INTENT_SHORTCUT_ONLY.motifBursts)
        assertFalse(AblationMode.INTENT_SHORTCUT_ONLY.rememberReplay)

        assertTrue(AblationMode.MOTIF_AND_FAST_A11Y.intentShortcuts)
        assertTrue(AblationMode.MOTIF_AND_FAST_A11Y.motifBursts)
        assertTrue(AblationMode.MOTIF_AND_FAST_A11Y.fastDeltaVerify)
        assertTrue(AblationMode.MOTIF_AND_FAST_A11Y.fastObservation)
        assertFalse(AblationMode.MOTIF_AND_FAST_A11Y.rememberReplay)

        assertTrue(AblationMode.FULL_HUMAN_SPEED.intentShortcuts)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.motifBursts)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.fastObservation)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.rememberReplay)

        assertEquals(AblationMode.FULL_HUMAN_SPEED, AblationMode.DEFAULT)
        assertEquals(AblationMode.BASELINE, AblationMode.fromId("baseline"))
        assertEquals(AblationMode.FULL_HUMAN_SPEED, AblationMode.fromId("nonsense"))
    }

    // ---------------------------------------------------------------------------------------------
    // Store: the exact file behaviour shared by the manager
    // ---------------------------------------------------------------------------------------------

    @Test
    fun storeAppendsHeaderOnceRewritesSummaryAndToleratesDamage() {
        val dir = java.io.File(context.cacheDir, "research-store-${System.nanoTime()}")
        val store = ResearchRunStore(dir)

        store.append(run(AblationMode.BASELINE, 10.0, 2, true))
        store.append(run(AblationMode.BASELINE, 20.0, 4, false))

        val runsFile = store.runsFile()
        val lines = runsFile.readLines()
        assertEquals(2 + 1, lines.size)
        assertEquals(ResearchRun.HEADER, lines.first())
        assertEquals(2, store.read().size)

        store.rewriteSummary()
        val summaryLines = store.summaryFile().readLines()
        assertEquals(ResearchSummaryTable.HEADER, summaryLines.first())
        assertEquals("BASELINE,2,1,15.00,3.00,50.0", summaryLines[1])

        runsFile.appendText("garbage,not,a,run\n")
        assertEquals("a damaged line is skipped, not fatal", 2, store.read().size)

        store.clear()
        assertFalse(store.runsFile().exists())
        assertFalse(store.summaryFile().exists())
    }

    // ---------------------------------------------------------------------------------------------
    // Manager: counters, files, logging, override
    // ---------------------------------------------------------------------------------------------

    @Test
    fun managerWritesRunAndSummaryAndLogsTheHudLine() {
        var now = 100_000L
        val logs = mutableListOf<String>()
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = { logs.add(it) })

        manager.startTask("Open Settings", AblationMode.BASELINE)
        now += 12_500
        manager.recordLlmCall()
        manager.recordStep()
        manager.markIntentShortcutUsed()
        val run = manager.endTask(isSuccess = true)

        requireNotNull(run)
        assertEquals(12.5, run.latencySeconds, 0.0001)
        assertEquals(1, run.llmCalls)
        assertEquals(1, run.steps)
        assertTrue(run.intentShortcutUsed)
        assertTrue(run.success)

        val runsText = manager.runsFile().readText()
        assertTrue(runsText.startsWith(ResearchRun.HEADER))
        assertEquals(2, runsText.trim().split("\n").size)

        val summaryText = manager.summaryFile().readText()
        assertTrue(summaryText.startsWith(ResearchSummaryTable.HEADER))
        assertTrue(summaryText.contains("BASELINE,1,1,12.50,1.00,100.0"))

        assertTrue(
            "HUD line must be logged verbatim: $logs",
            logs.contains("[BASELINE] Latency: 12.50s | LLM Calls: 1 | Success Rate: 100.0%")
        )
        assertTrue(logs.any { it.startsWith("TASK_END mode=BASELINE latency_s=12.500") })
    }

    @Test
    fun overrideRewritesTheLastRowInsteadOfAppending() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})

        manager.startTask("first", AblationMode.BASELINE)
        now += 10_000
        manager.endTask(isSuccess = true)

        manager.startTask("second", AblationMode.BASELINE)
        now += 20_000
        manager.endTask(isSuccess = true)

        val updated = manager.overrideLastOutcome(false)
        requireNotNull(updated)
        assertFalse(updated.success)
        assertTrue(updated.manuallyOverridden)
        assertEquals("second", updated.goal)

        val rows = manager.allRuns()
        assertEquals(2, rows.size)
        assertTrue(rows.last().manuallyOverridden)
        assertFalse(rows.last().success)

        val summary = manager.summaryFor(AblationMode.BASELINE)
        requireNotNull(summary)
        assertEquals(2, summary.runs)
        assertEquals(50.0, summary.successRatePercent, 0.0001)
        assertEquals(15.0, summary.meanLatencySeconds, 0.0001)
    }

    @Test
    fun modeIsPersistedAcrossManagerInstances() {
        val first = ResearchTelemetryManager(context, logger = {})
        first.mode = AblationMode.MOTIF_AND_FAST_A11Y
        val second = ResearchTelemetryManager(context, logger = {})
        assertEquals(AblationMode.MOTIF_AND_FAST_A11Y, second.mode)
    }

    @Test
    fun countersOutsideATaskAreIgnoredAndEndTaskReturnsNull() {
        val manager = ResearchTelemetryManager(context, logger = {})
        manager.recordLlmCall()
        manager.recordStep()
        manager.markRememberCacheHit()
        assertNull(manager.endTask(isSuccess = true))
        assertFalse(manager.isTaskActive)
        assertTrue(manager.allRuns().isEmpty())
    }

    @Test
    fun eachTaskStartsFromZeroCounters() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})
        manager.startTask("first", AblationMode.BASELINE)
        repeat(5) { manager.recordLlmCall() }
        now += 1_000
        manager.endTask(isSuccess = false, failureReason = "unmet_criteria")

        manager.startTask("second", AblationMode.BASELINE)
        manager.recordLlmCall()
        now += 1_000
        val second = manager.endTask(isSuccess = true)

        requireNotNull(second)
        assertEquals(1, second.llmCalls)
        assertEquals(0, second.steps)
        assertEquals("unmet_criteria", manager.allRuns().first().failureReason)
    }

    @Test
    fun endTaskIfActiveRecordsCancelledRunsAsFailuresOnlyOnce() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})

        manager.startTask("cancelled task", AblationMode.BASELINE)
        now += 7_000
        val aborted = manager.endTaskIfActive("stopped")
        requireNotNull(aborted)
        assertFalse(aborted.success)
        assertEquals("stopped", aborted.failureReason)
        assertEquals(7.0, aborted.latencySeconds, 0.0001)
        assertFalse(manager.isTaskActive)

        // A normal end already closed the run, so the safety net must not append a second row.
        assertNull(manager.endTaskIfActive("stopped"))
        assertEquals(1, manager.allRuns().size)
    }

    @Test
    fun clearAllRemovesStoredFiles() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})
        manager.startTask("task", AblationMode.FULL_HUMAN_SPEED)
        now += 2_000
        manager.endTask(isSuccess = true)
        assertTrue(manager.runsFile().exists())

        manager.clearAll()
        assertFalse(manager.runsFile().exists())
        assertFalse(manager.summaryFile().exists())
        assertNull(manager.lastRunOrNull())
    }

    private fun run(
        mode: AblationMode,
        latency: Double,
        calls: Int,
        success: Boolean
    ) = ResearchRun(
        timestampMs = 1L,
        mode = mode,
        goal = "goal",
        latencySeconds = latency,
        llmCalls = calls,
        localInferences = 0,
        steps = 1,
        success = success,
        failureReason = if (success) "" else "unmet_criteria",
        intentShortcutUsed = false,
        rememberCacheHit = false
    )
}
