package com.aniob.core.verification

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Plan6VerificationTest {

    private fun step(n: Int, desc: String, dispatched: Boolean = true) =
        CheckerInvariants.RecordedStep(n, desc, dispatched, verified = dispatched)

    private fun probeEvidence(excerpt: String, step: Int? = 3) =
        Evidence(EvidenceKind.PROBE_OUTPUT, "probe_alarms", excerpt, step)

    // ------------------------------------------------- 6.1 the tool table IS the enforcement

    private val table = object : DeviceProbeTable {
        override val supported = setOf(ProbeKind.ALARMS, ProbeKind.BATTERY)
        override suspend fun read(request: ProbeRequest) =
            ProbeResult(request.kind, ok = true, output = "07:00 enabled")
    }

    @Test
    fun `a probe outside the table does not exist`() = runBlocking {
        val out = EnumeratedProbeTable(table).run("tap_screen")
        assertTrue(out is EnumeratedProbeTable.Outcome.NoSuchProbe)
        assertTrue((out as EnumeratedProbeTable.Outcome.NoSuchProbe).message.contains("do not exist"))
    }

    @Test
    fun `a declared but unavailable probe is inconclusive, not failed`() = runBlocking {
        val out = EnumeratedProbeTable(table).run("probe_notifications")
        assertTrue(out is EnumeratedProbeTable.Outcome.Unsupported)
        assertTrue((out as EnumeratedProbeTable.Outcome.Unsupported).message.contains("INCONCLUSIVE"))
    }

    @Test
    fun `a probe that throws becomes an unusable result, not a crash`() = runBlocking {
        val broken = object : DeviceProbeTable {
            override val supported = setOf(ProbeKind.ALARMS)
            override suspend fun read(request: ProbeRequest): ProbeResult = throw IllegalStateException("boom")
        }
        val out = EnumeratedProbeTable(broken).run("probe_alarms")
        assertTrue(out is EnumeratedProbeTable.Outcome.Ran)
        assertFalse((out as EnumeratedProbeTable.Outcome.Ran).result.isUsableEvidence)
    }

    // ------------------------------------------------- 6.2 three-valued verdicts

    @Test
    fun `a failed verdict with no concrete evidence is downgraded to inconclusive`() {
        val j = Judgement(CheckItem.Verify("an alarm is set for 07:00"), Verdict.FAILED, suggestion = "set it")
        val sealed = CheckerInvariants.seal(j, emptyList())
        assertEquals(Verdict.INCONCLUSIVE, sealed.verdict)
        assertTrue(sealed.downgrades.any { it.contains("no concrete evidence") })
    }

    @Test
    fun `a passed verdict with no concrete evidence is also downgraded`() {
        val j = Judgement(CheckItem.Verify("an alarm is set for 07:00"), Verdict.PASSED)
        assertEquals(Verdict.INCONCLUSIVE, CheckerInvariants.seal(j, emptyList()).verdict)
    }

    @Test
    fun `a verdict backed by a probe survives`() {
        val j = Judgement(
            CheckItem.Verify("an alarm is set for 07:00"), Verdict.PASSED,
            evidence = listOf(probeEvidence("07:00 enabled"))
        )
        val sealed = CheckerInvariants.seal(j, emptyList())
        assertEquals(Verdict.PASSED, sealed.verdict)
        assertTrue(sealed.downgrades.isEmpty())
    }

    @Test
    fun `the item is quoted verbatim, never paraphrased`() {
        val text = "an alarm is set for 07:00 on weekdays"
        val sealed = CheckerInvariants.seal(
            Judgement(CheckItem.Verify(text), Verdict.PASSED, evidence = listOf(probeEvidence("07:00 enabled"))),
            emptyList()
        )
        assertEquals("\"$text\"", sealed.quotedItem())
        assertTrue(sealed.render().contains(text))
    }

    // ------------------------------------------------- 6.3 verify vs assert

    @Test
    fun `a failed verify without a suggestion cannot be used as a repair instruction`() {
        val j = Judgement(
            CheckItem.Verify("the message was sent"), Verdict.FAILED,
            evidence = listOf(Evidence(EvidenceKind.SCREEN_OBSERVATION, "step 4", "the compose field still has text"))
        )
        val sealed = CheckerInvariants.seal(j, emptyList())
        assertEquals(Verdict.INCONCLUSIVE, sealed.verdict)
        assertTrue(sealed.downgrades.any { it.contains("actionable suggestion") })
    }

    @Test
    fun `an execution problem is never reported as a device defect`() {
        val item = CheckItem.Assert("the alarm rings at 07:00", triggeringActionDescription = "TAP_Save")
        val j = Judgement(item, Verdict.FAILED, evidence = listOf(probeEvidence("no alarms")))

        // The Save tap never dispatched, so the device is not the thing that is broken.
        val notPerformed = CheckerInvariants.seal(j, listOf(step(4, "TAP_Save", dispatched = false)))
        assertEquals(Verdict.INCONCLUSIVE, notPerformed.verdict)
        assertTrue(notPerformed.downgrades.any { it.contains("not confirmed in history") })

        // With the action confirmed, a failed assert is a legitimate, valuable result.
        val performed = CheckerInvariants.seal(j, listOf(step(4, "TAP_Save")))
        assertEquals(Verdict.FAILED, performed.verdict)
    }

    @Test
    fun `nothing tries to repair an assert`() {
        val item = CheckItem.Assert("the alarm rings at 07:00", "TAP_Save")
        val sealed = CheckerInvariants.seal(
            Judgement(item, Verdict.FAILED, listOf(probeEvidence("no alarms")), suggestion = "tap save again"),
            listOf(step(4, "TAP_Save"))
        )
        assertNull(sealed.suggestion)
        assertFalse(CheckerInvariants.mayRepair(item, 0))
    }

    @Test
    fun `repairs are capped at two`() {
        val item = CheckItem.Verify("the message was sent")
        assertTrue(CheckerInvariants.mayRepair(item, 0))
        assertTrue(CheckerInvariants.mayRepair(item, 1))
        assertFalse(CheckerInvariants.mayRepair(item, 2))
    }

    // ------------------------------------------------- 6.4 evidence anchoring

    @Test
    fun `the live screen is not evidence for a transient expectation`() {
        val item = CheckItem.Assert("a toast said Saved", "TAP_Save", isTransientOrOrdering = true)
        val live = Evidence(EvidenceKind.SCREEN_OBSERVATION, "live screen", "no toast visible", atStep = null)
        val sealed = CheckerInvariants.seal(
            Judgement(item, Verdict.FAILED, listOf(live)), listOf(step(4, "TAP_Save"))
        )
        assertTrue(sealed.evidence.isEmpty())
        assertEquals(Verdict.INCONCLUSIVE, sealed.verdict)
        assertTrue(sealed.downgrades.any { it.contains("recorded history") })
    }

    @Test
    fun `a final check at task exit may use the final screen`() {
        val item = CheckItem.Assert("a toast said Saved", "TAP_Save", isTransientOrOrdering = true)
        val live = Evidence(EvidenceKind.SCREEN_OBSERVATION, "final screen", "toast reading \"Saved\"", atStep = null)
        val sealed = CheckerInvariants.seal(
            Judgement(item, Verdict.PASSED, listOf(live)), listOf(step(4, "TAP_Save")), isFinalCheck = true
        )
        assertEquals(Verdict.PASSED, sealed.verdict)
        assertEquals(1, sealed.evidence.size)
    }

    // ------------------------------------------------- 6.5 verdict vs release

    private fun sealedOf(item: CheckItem, v: Verdict, sug: String? = null) = SealedJudgement(
        item, v, listOf(probeEvidence("x")), sug, "", emptyList()
    )

    @Test
    fun `release is computed from verdicts and never rewrites them`() {
        val js = listOf(sealedOf(CheckItem.Verify("a"), Verdict.PASSED))
        val d = ReleaseDecision.decide(js, ReleaseDecision.Level.CHECKPOINTS)
        assertEquals(ReleaseDecision.Outcome.RELEASE, d.outcome)
        assertEquals(Verdict.PASSED, js[0].verdict)   // untouched
    }

    @Test
    fun `a failed verify routes to repair with its suggestion`() {
        val d = ReleaseDecision.decide(
            listOf(sealedOf(CheckItem.Verify("the message was sent"), Verdict.FAILED, "the send button was not tapped")),
            ReleaseDecision.Level.CHECKPOINTS
        )
        assertEquals(ReleaseDecision.Outcome.REPAIR, d.outcome)
        assertEquals(listOf("the send button was not tapped"), d.repairSuggestions)
    }

    @Test
    fun `a spent repair budget turns a failed verify into a halt`() {
        val d = ReleaseDecision.decide(
            listOf(sealedOf(CheckItem.Verify("x"), Verdict.FAILED, "do y")),
            ReleaseDecision.Level.CHECKPOINTS, repairsUsed = 2
        )
        assertEquals(ReleaseDecision.Outcome.HALT, d.outcome)
    }

    @Test
    fun `a failed assert halts and is never repaired`() {
        val d = ReleaseDecision.decide(
            listOf(sealedOf(CheckItem.Assert("the alarm rings", "TAP_Save"), Verdict.FAILED)),
            ReleaseDecision.Level.CHECKPOINTS
        )
        assertEquals(ReleaseDecision.Outcome.HALT, d.outcome)
        assertTrue(d.repairSuggestions.isEmpty())
    }

    @Test
    fun `inconclusive proceeds with a visible caveat, but blocks under strict`() {
        val js = listOf(sealedOf(CheckItem.Verify("the receipt was shown"), Verdict.INCONCLUSIVE))
        val normal = ReleaseDecision.decide(js, ReleaseDecision.Level.CHECKPOINTS)
        assertEquals(ReleaseDecision.Outcome.RELEASE_WITH_CAVEAT, normal.outcome)
        assertTrue(normal.proceeds)
        assertNotNull(normal.userVisibleCaveat)

        val strict = ReleaseDecision.decide(js, ReleaseDecision.Level.STRICT)
        assertEquals(ReleaseDecision.Outcome.HALT, strict.outcome)
        assertFalse(strict.proceeds)
    }

    @Test
    fun `a broken checker fails open with a caveat`() {
        val d = ReleaseDecision.decide(emptyList(), ReleaseDecision.Level.STRICT, checkerError = "probe timeout")
        assertTrue(d.proceeds)
        assertNotNull(d.userVisibleCaveat)
    }

    // ------------------------------------------------- 6.6 verification level

    @Test
    fun `verification level gates when checking happens`() {
        assertFalse(ReleaseDecision.Level.OFF.checksAtFinal())
        assertFalse(ReleaseDecision.Level.FINAL.checksAtCheckpoint())
        assertTrue(ReleaseDecision.Level.FINAL.checksAtFinal())
        assertTrue(ReleaseDecision.Level.CHECKPOINTS.checksAtCheckpoint())
        assertTrue(ReleaseDecision.Level.STRICT.checksAtCheckpoint())
    }
}
