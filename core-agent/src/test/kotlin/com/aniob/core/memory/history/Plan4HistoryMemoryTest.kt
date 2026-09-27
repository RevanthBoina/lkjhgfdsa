package com.aniob.core.memory.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan-4 acceptance tests.
 *
 * Every test below is written against the SPEC sentence it quotes, not against the implementation,
 * and each one failed before the corresponding fix landed.
 */
class Plan4HistoryMemoryTest {

    // ---------------------------------------------------------------- 4.2 neutral wording

    @Test
    fun `banned verdict words are rejected in the agent's own voice`() {
        val bad = "I successfully arrived at settings and the agent completed the task."
        val report = NeutralWordingContract.validate(bad)
        assertFalse(report.isValid)
        assertTrue(report.violations.any { it.contains("successfully") })
        assertTrue(report.violations.any { it.contains("arrived at") })
        assertTrue(report.violations.any { it.contains("completed") })
        assertTrue(report.violations.any { it.contains("the agent") })

        val good = "The settings screen was displayed. I tapped the search bar."
        assertTrue(NeutralWordingContract.validate(good).isValid)
    }

    @Test
    fun `4_4 verbatim on-screen text may contain a banned word`() {
        // A real Android toast. 4.4 orders us to transcribe it verbatim; 4.2 must not veto that.
        val s = "I observed a toast reading \"Download completed\" on the after screen."
        assertTrue(NeutralWordingContract.validate(s).violations.toString(),
            NeutralWordingContract.validate(s).isValid)

        // ...but the same word in the agent's own voice is still a violation.
        assertFalse(NeutralWordingContract.validate("I completed the download.").isValid)
    }

    @Test
    fun `violations come back as regeneration feedback`() {
        val r = NeutralWordingContract.validate("I successfully tapped it.")
        assertTrue(r.asRegenerationFeedback().contains("successfully"))
        assertTrue(r.asRegenerationFeedback().isNotBlank())
    }

    @Test
    fun `machine strings are neutralized`() {
        val out = NeutralWordingContract.neutralize("Action failed: could not complete")
        assertTrue(NeutralWordingContract.validate(out).isValid)
    }

    @Test
    fun `4_3 fact priority resolves conflicts`() {
        val user = NeutralWordingContract.FactPriority.USER_INSTRUCTION to "user says Bombay"
        val guess = NeutralWordingContract.FactPriority.AGENT_REASONING to "agent guessed Delhi"
        assertEquals("user says Bombay", NeutralWordingContract.resolveConflict(user, guess))
        assertEquals("user says Bombay", NeutralWordingContract.resolveConflict(guess, user))
    }

    // ---------------------------------------------------------------- 4.1 async summaries

    @Test
    fun `the loop never waits and a ready summary is returned`() {
        val service = AsyncStepSummaryService.simple { stepId, detail -> "I observed $detail at step $stepId" }
        val before = service.getSummaryOrFallback("step-1", "DETAIL")
        assertFalse(before.isReady)
        assertEquals("DETAIL", before.render())

        service.dispatchSummary("step-1", "the settings page")
        assertTrue(service.awaitQuiescence(5))
        assertTrue(service.getSummaryOrFallback("step-1", "DETAIL").isReady)
        service.shutdown()
    }

    @Test
    fun `retry feeds the violation back so regeneration can converge`() {
        val attempts = AtomicInteger(0)
        val service = AsyncStepSummaryService(
            generator = { _, _, priorViolations ->
                attempts.incrementAndGet()
                if (priorViolations.isEmpty()) "I successfully tapped send."   // rejected
                else "I tapped the send control."                              // fixed on feedback
            }
        )
        service.dispatchSummary("step-1", "x")
        service.awaitQuiescence(5)
        assertTrue(service.getSummaryOrFallback("step-1", "FB").isReady)
        assertEquals(2, attempts.get())
        assertEquals(1, service.stats().retries)
        service.shutdown()
    }

    @Test
    fun `a permanently unfixable summary is reported, never silently dropped`() {
        val reported = mutableListOf<String>()
        val service = AsyncStepSummaryService(
            generator = { _, _, _ -> "I successfully did it." },
            onPermanentFailure = { id, _ -> synchronized(reported) { reported += id } }
        )
        service.dispatchSummary("step-9", "x")
        service.awaitQuiescence(5)
        assertFalse(service.getSummaryOrFallback("step-9", "FB").isReady)
        assertEquals(listOf("step-9"), reported)
        assertEquals(1, service.stats().permanentDrops)
        service.shutdown()
    }

    @Test
    fun `workers are daemon threads`() {
        val service = AsyncStepSummaryService()
        service.dispatchSummary("step-1", "x")
        service.awaitQuiescence(5)
        val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("aniob-step-summary-") }
        assertTrue("expected daemon workers, got $workers", workers.isNotEmpty() && workers.all { it.isDaemon })
        service.shutdown()
    }

    @Test
    fun `awaitQuiescence is a barrier, not a shutdown`() {
        val service = AsyncStepSummaryService.simple { _, d -> "I observed $d." }
        service.dispatchSummary("step-1", "one")
        service.awaitQuiescence(5)
        service.dispatchSummary("step-2", "two")     // must still work
        service.awaitQuiescence(5)
        assertTrue(service.getSummaryOrFallback("step-2", "FB").isReady)
        service.shutdown()
    }

    @Test
    fun `a summary survives late alias registration`() {
        val service = AsyncStepSummaryService.simple { _, d -> "I observed $d." }
        service.dispatchSummary("tmp-7", "the inbox")
        service.awaitQuiescence(5)
        service.registerAlias("tmp-7", "step-7")
        assertTrue(service.getSummaryOrFallback("tmp-7", "FB").isReady)
        assertTrue(service.getSummaryOrFallback("step-7", "FB").isReady)
        service.shutdown()
    }

    // ---------------------------------------------------------------- 4.8 frozen messages

    @Test
    fun `4_8 a frozen step is never backfilled`() {
        val service = AsyncStepSummaryService.simple { _, d -> "I observed $d." }
        service.freezeStep("step-1")
        service.dispatchSummary("step-1", "late")
        service.awaitQuiescence(5)
        assertFalse(service.getSummaryOrFallback("step-1", "FB").isReady)
        assertTrue(service.isFrozen("step-1"))
        service.shutdown()
    }

    // ---------------------------------------------------------------- 4.6 scrub edges + budget

    private fun turn(n: Int, tok: Int = 500) = TwoEdgeContextScrubber.HistoryTurn(
        stepNumber = n, stepId = "step-$n", userPrompt = "u$n",
        uiElementList = listOf("[1] Settings", "[2] Profile"),
        planRecitation = "Plan: tap settings",
        ephemeralBlocks = listOf("per-turn nudge"),
        screenshotRef = "shot$n.png", visualSummary = "I saw a screen.", tokenEstimate = tok
    )

    private fun occ(active: Int) = HistoryBudget.Occupancy(0, 0, 0, 0, active)

    @Test
    fun `text edge strips elements, plan and ephemerals from every older turn`() {
        val turns = listOf(turn(1), turn(2))
        TwoEdgeContextScrubber().scrub(turns, occ(1_000))

        assertNull(turns[0].uiElementList)
        assertNull(turns[0].planRecitation)
        assertNull(turns[0].ephemeralBlocks)   // 5.3: deleted, not frozen
        assertTrue(turns[0].textScrubbed)

        assertNotNull(turns[1].uiElementList)  // depth 0 is the live observation
        assertEquals(2, turns[1].uiElementList!!.size)
    }

    @Test
    fun `scrubbing is monotonic - a replaced screenshot never comes back`() {
        val turns = (1..10).map { turn(it, tok = 6_000) }
        val s = TwoEdgeContextScrubber()
        s.scrub(turns, occ(60_000))                       // high occupancy -> image edge 3
        assertNull("depth-5 screenshot should be gone", turns[4].screenshotRef)

        s.scrub(turns, occ(1_000))                        // pressure drops -> edge relaxes to 6
        assertNull("a scrubbed screenshot must never be restored", turns[4].screenshotRef)
    }

    @Test
    fun `passing both edges freezes the turn exactly once`() {
        val turns = (1..8).map { turn(it, tok = 6_000) }
        val out = TwoEdgeContextScrubber().scrub(turns, occ(48_000))
        assertTrue(out.newlyFrozenSteps.isNotEmpty())
        assertTrue(turns[0].isFrozen)
        val again = TwoEdgeContextScrubber().scrub(turns, occ(48_000))
        assertTrue("already-frozen turns must not re-report", again.newlyFrozenSteps.isEmpty())
    }

    @Test
    fun `the occupancy ladder has all three gates and respects minActiveSteps`() {
        val b = HistoryBudget.DEFAULT
        assertEquals(HistoryBudget.Pressure.LOW, b.pressureOf(0.20))
        assertEquals(HistoryBudget.Pressure.START, b.pressureOf(0.40))
        assertEquals(HistoryBudget.Pressure.SOFT, b.pressureOf(0.75))
        assertEquals(HistoryBudget.Pressure.HARD, b.pressureOf(0.95))

        // 6 active steps, min 5 -> only 1 foldable, below chunkMinSteps -> nothing folds
        assertNull(b.plan(HistoryBudget.Occupancy(0, 0, 0, 0, 76_000), (1..6).toList()).chunkRange)
        // 12 active steps -> 7 foldable -> folds
        val plan = b.plan(HistoryBudget.Occupancy(0, 0, 0, 0, 76_000), (1..12).toList())
        assertNotNull(plan.chunkRange)
        assertTrue(plan.chunkRange!!.last <= 7)
    }

    @Test
    fun `occupancy counts the whole prompt, not just the turns`() {
        val b = HistoryBudget.DEFAULT
        val turnsOnly = HistoryBudget.Occupancy(0, 0, 0, 0, 20_000)
        val whole = HistoryBudget.Occupancy(
            systemPrefixTokens = 25_000, chunkTokens = 8_000,
            noteTokens = 2_000, toolSchemaTokens = 6_000, activeTurnTokens = 20_000
        )
        assertEquals(HistoryBudget.Pressure.LOW, b.pressureOf(b.ratioOf(turnsOnly)))
        assertEquals(HistoryBudget.Pressure.SOFT, b.pressureOf(b.ratioOf(whole)))
    }

    // ---------------------------------------------------------------- 4.5 three-band chunks

    private fun synopsis(effect: String = "A list was shown", keys: List<String> = emptyList()) =
        ThreeBandChunk.SegmentSynopsis(
            doing = "I am opening the inbox", did = "I opened a list", effect = effect,
            entryState = "home", exitState = "inbox", noteKeysWritten = keys
        )

    private fun ledger(range: IntRange) = range.map {
        ThreeBandChunk.LedgerEntry(it, "tap", "t$it", "the expected change was observed")
    }

    @Test
    fun `a chunk shorter than three steps is rejected`() {
        val c = ThreeBandChunk(0, 5, 5, synopsis(), listOf(ThreeBandChunk.Interval(5, 5, "I tapped once.")), ledger(5..5))
        assertTrue(ThreeBandChunk.validateChunk(c).any { it.contains("minimum is 3") })
    }

    @Test
    fun `band 2 gaps and overlaps are detected`() {
        val gap = ThreeBandChunk(0, 1, 4, synopsis(),
            listOf(ThreeBandChunk.Interval(1, 2, "I opened it."), ThreeBandChunk.Interval(4, 4, "I tapped.")),
            ledger(1..4))
        assertTrue(ThreeBandChunk.validateChunk(gap).any { it.contains("gap") })

        val overlap = ThreeBandChunk(1, 1, 4, synopsis(),
            listOf(ThreeBandChunk.Interval(1, 3, "a"), ThreeBandChunk.Interval(3, 4, "b")),
            ledger(1..4))
        assertTrue(ThreeBandChunk.validateChunk(overlap).any { it.contains("overlap") })
    }

    @Test
    fun `note keys written must appear in effect`() {
        val c = ThreeBandChunk(0, 1, 3, synopsis(keys = listOf("order_id")),
            listOf(ThreeBandChunk.Interval(1, 3, "I opened it.")), ledger(1..3))
        assertTrue(ThreeBandChunk.validateChunk(c).any { it.contains("order_id") })
    }

    @Test
    fun `band 2 rejects coordinates and stale element indices`() {
        val c = ThreeBandChunk(0, 1, 3, synopsis(),
            listOf(ThreeBandChunk.Interval(1, 3, "I tapped element [4] at x=880 y=1210.")), ledger(1..3))
        val v = ThreeBandChunk.validateChunk(c)
        assertTrue("stale index or coordinate not caught: $v", v.any { it.contains("coordinate or element index") })
    }

    @Test
    fun `a clean chunk validates`() {
        val c = ThreeBandChunk(0, 1, 4, synopsis(effect = "A list containing order_id was shown", keys = listOf("order_id")),
            listOf(ThreeBandChunk.Interval(1, 2, "I opened the list."), ThreeBandChunk.Interval(3, 4, "I scrolled it.")),
            ledger(1..4))
        assertEquals(emptyList<String>(), ThreeBandChunk.validateChunk(c))
    }

    // ---------------------------------------------------------------- 4.7 searchable history

    @Test
    fun `recall is capped and thread-safe`() {
        val index = SearchableHistoryIndex()
        val threads = (1..4).map { w ->
            Thread { repeat(500) { i -> index.indexStep(w * 1000 + i, "the settings page was shown", tokenCount = 400) } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(2000, index.size())

        val r = index.recall(keyword = "settings")
        assertTrue("recall must be capped, got ${r.stepNumbers.size}", r.stepNumbers.size <= 5)
        assertTrue(r.truncated)
        assertEquals(2000, r.totalMatches)
    }

    @Test
    fun `replay cap counts steps that exist`() {
        val index = SearchableHistoryIndex()
        (1..5).forEach { index.indexStep(it, "c$it", tokenCount = 100) }
        assertEquals(5, index.replaySteps(listOf(99, 98, 1, 2, 3, 4, 5)).size)
    }

    @Test
    fun `search still resolves a keyword to a step number`() {
        val index = SearchableHistoryIndex()
        index.indexStep(1, "the home screen was shown")
        index.indexStep(2, "the settings page was shown, I tapped display")
        index.indexStep(3, "a dark theme option was shown")
        assertEquals(listOf(2), index.search(keyword = "settings"))
    }

    // ---------------------------------------------------------------- the store (the wiring)

    @Test
    fun `the store folds old spans, keeps recall, and renders a withheld-elements marker`() {
        val store = StepHistoryStore(
            // Small budget so 14 turns at ~2k tokens each cross the soft/hard gates.
            budget = HistoryBudget(totalTokens = 20_000, minActiveSteps = 5),
            summaries = AsyncStepSummaryService.simple { _, d -> "I observed: $d" },
            estimateTokens = { 2_000 }
        )
        repeat(14) { i ->
            store.append(
                stepNumber = i + 1, userPrompt = "do the thing",
                detail = "I ran tap on \"Row ${i + 1}\". The expected change was observed.",
                uiElementList = listOf("[1] Row ${i + 1}"), screenshotRef = "s${i + 1}.png",
                actionType = "tap", targetDescription = "Row ${i + 1}", recordedResult = "observed"
            )
        }
        store.awaitSummaries(5)

        assertTrue("expected at least one folded chunk", store.chunkCount >= 1)
        assertTrue("active window must shrink", store.activeTurnCount < 14)

        val rendered = store.render()
        assertTrue(rendered.contains(TwoEdgeContextScrubber.ELEMENTS_WITHHELD_MARKER))
        assertTrue(rendered.contains("[compressed steps"))

        // Compression must not destroy recall.
        val recalled = store.recall(keyword = "Row 2")
        assertTrue(recalled.stepNumbers.isNotEmpty())
        assertTrue(store.replay(recalled.stepNumbers).isNotEmpty())

        store.shutdown()
    }

    @Test
    fun `the rendered history contains no banned verdict words`() {
        val store = StepHistoryStore(summaries = AsyncStepSummaryService.simple { _, d -> "I observed: $d" })
        store.append(
            stepNumber = 1, userPrompt = "send it", detail = "I ran tap on \"Send\".",
            executionResult = NeutralWordingContract.neutralize("dispatch failed: could not resolve target"),
            actionType = "tap", targetDescription = "Send", recordedResult = "no change"
        )
        store.awaitSummaries(5)
        val report = NeutralWordingContract.validate(store.render())
        assertTrue("history leaked a verdict word: ${report.violations}", report.isValid)
        store.shutdown()
    }
}
