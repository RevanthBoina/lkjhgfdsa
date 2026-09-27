package com.aniob.core.verification

/**
 * Plan-6.5 / 6.6 — SEPARATE VERDICT FROM RELEASE.
 *
 * The decision to proceed is computed by the CALLER from the verdicts, and is fail-open on errors.
 * Releasing must never rewrite a verdict. Keeping the two steps apart is what stops the agent
 * arguing its way past a failed check: it can influence what the verdict says, but it has no
 * access at all to what the verdict *means*.
 */
object ReleaseDecision {

    /** Plan-6.6: VERIFICATION LEVEL IS A USER SETTING. */
    enum class Level {
        /** No checking at all. Fastest, and honest about it. */
        OFF,

        /** Check only at task exit. */
        FINAL,

        /** Check at each declared checkpoint and at exit. */
        CHECKPOINTS,

        /** Checkpoints, plus an INCONCLUSIVE is treated as a blocker rather than a pass. */
        STRICT;

        fun checksAtCheckpoint(): Boolean = this == CHECKPOINTS || this == STRICT
        fun checksAtFinal(): Boolean = this != OFF
    }

    enum class Outcome {
        /** Proceed. */
        RELEASE,

        /** Send back for repair; a verify failed and named what is missing. */
        REPAIR,

        /** Stop and report; the device did not do what was asserted. */
        HALT,

        /** Could not tell. Under STRICT this blocks; otherwise it proceeds with a caveat. */
        RELEASE_WITH_CAVEAT
    }

    data class Decision(
        val outcome: Outcome,
        val reason: String,
        /** Repair instructions, in order, from the failed verify items. */
        val repairSuggestions: List<String> = emptyList(),
        /** Shown in the timeline so an unverified release is never invisible. */
        val userVisibleCaveat: String? = null
    ) {
        val proceeds: Boolean get() = outcome == Outcome.RELEASE || outcome == Outcome.RELEASE_WITH_CAVEAT
    }

    /**
     * Computes the decision. Never mutates a judgement.
     *
     * FAIL-OPEN on errors: if checking itself broke, that is a fact about the checker, not about
     * the task, and blocking the user because our verifier threw is the wrong trade.
     */
    fun decide(
        judgements: List<SealedJudgement>,
        level: Level,
        repairsUsed: Int = 0,
        checkerError: String? = null
    ): Decision {
        if (checkerError != null) {
            return Decision(
                Outcome.RELEASE_WITH_CAVEAT,
                "the checker did not run: $checkerError",
                userVisibleCaveat = "I could not verify this step, so I am continuing without a check."
            )
        }
        if (level == Level.OFF) {
            return Decision(Outcome.RELEASE, "verification is off")
        }

        val failedAsserts = judgements.filter { it.item is CheckItem.Assert && it.verdict == Verdict.FAILED }
        if (failedAsserts.isNotEmpty()) {
            return Decision(
                Outcome.HALT,
                "the device did not match ${failedAsserts.size} asserted expectation(s): " +
                    failedAsserts.joinToString("; ") { it.quotedItem() }
            )
        }

        val failedVerifies = judgements.filter { it.item is CheckItem.Verify && it.verdict == Verdict.FAILED }
        if (failedVerifies.isNotEmpty()) {
            val repairable = failedVerifies.filter { CheckerInvariants.mayRepair(it.item, repairsUsed) }
            return if (repairable.isNotEmpty()) {
                Decision(
                    Outcome.REPAIR,
                    "${repairable.size} acceptance criterion/criteria not met",
                    repairSuggestions = repairable.mapNotNull { it.suggestion }
                )
            } else {
                Decision(
                    Outcome.HALT,
                    "acceptance criteria not met and the repair budget is spent: " +
                        failedVerifies.joinToString("; ") { it.quotedItem() }
                )
            }
        }

        val inconclusive = judgements.filter { it.verdict == Verdict.INCONCLUSIVE }
        if (inconclusive.isNotEmpty()) {
            return if (level == Level.STRICT) {
                Decision(
                    Outcome.HALT,
                    "strict verification: ${inconclusive.size} check(s) could not be evaluated: " +
                        inconclusive.joinToString("; ") { it.quotedItem() }
                )
            } else {
                Decision(
                    Outcome.RELEASE_WITH_CAVEAT,
                    "${inconclusive.size} check(s) were inconclusive",
                    userVisibleCaveat = "I could not confirm: " +
                        inconclusive.joinToString("; ") { it.item.text }
                )
            }
        }

        return Decision(Outcome.RELEASE, "all ${judgements.size} check(s) passed")
    }
}
