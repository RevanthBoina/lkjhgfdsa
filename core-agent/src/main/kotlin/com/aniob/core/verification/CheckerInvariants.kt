package com.aniob.core.verification

/**
 * Plan-6.2 / 6.3 / 6.4 — the rules that are applied to a checker's raw output in CODE.
 *
 * Every rule here exists because a model asked politely will break it. The checker prompt should
 * still state them, but the prompt is the request and this file is the guarantee.
 */
object CheckerInvariants {

    /** One recorded step, as the checker is allowed to see it. Read-only by construction. */
    data class RecordedStep(
        val stepNumber: Int,
        val actionDescription: String,
        val dispatched: Boolean,
        val verified: Boolean,
        val observedText: String? = null
    )

    /**
     * Applies, in order:
     *
     *  1. **Precondition first (6.3).** Before judging an `assert:`, confirm from history that the
     *     triggering action was actually and correctly performed. If it was not, the answer is
     *     INCONCLUSIVE — never failed. A failed assert is attributed to the DEVICE, and an
     *     execution problem must not be reported as a device defect.
     *  2. **Evidence anchoring (6.4).** For transient and ordering expectations the live screen is
     *     not valid evidence, because the device has moved on. Such evidence is dropped.
     *  3. **No evidence, no failure (6.2).** A `failed` verdict with no concrete evidence is
     *     invalid and is downgraded to INCONCLUSIVE.
     *  4. **A failed `verify:` must be actionable (6.3).** Without a suggestion the step cannot be
     *     repaired, so the failure is not usable as a repair instruction.
     *
     * @param isFinalCheck a final check at task exit may use the final screen
     */
    fun seal(
        judgement: Judgement,
        history: List<RecordedStep>,
        isFinalCheck: Boolean = false
    ): SealedJudgement {
        val downgrades = mutableListOf<String>()
        var verdict = judgement.verdict
        var evidence = judgement.evidence

        // 2. evidence anchoring
        val item = judgement.item
        val transientOrOrdering = item is CheckItem.Assert && item.isTransientOrOrdering
        if (transientOrOrdering && !isFinalCheck) {
            val before = evidence.size
            evidence = evidence.filterNot { it.kind == EvidenceKind.SCREEN_OBSERVATION && it.atStep == null }
            if (evidence.size != before) {
                downgrades += "dropped ${before - evidence.size} live-screen observation(s): this is a " +
                    "transient or ordering expectation, so it must be judged from recorded history"
            }
        }

        // 1. precondition first
        if (item is CheckItem.Assert) {
            val performed = history.any {
                it.dispatched && it.actionDescription.equals(item.triggeringActionDescription, ignoreCase = true)
            }
            if (!performed) {
                if (verdict == Verdict.FAILED) {
                    downgrades += "the triggering action '${item.triggeringActionDescription}' is not " +
                        "confirmed in history, so this cannot be attributed to the device"
                }
                verdict = Verdict.INCONCLUSIVE
            }
        }

        // 3. no concrete evidence, no failure
        if (verdict == Verdict.FAILED && evidence.none { it.isConcrete }) {
            downgrades += "downgraded from FAILED: no concrete evidence was supplied"
            verdict = Verdict.INCONCLUSIVE
        }
        // ...and no pass either. A pass asserted from nothing is the same defect wearing a smile.
        if (verdict == Verdict.PASSED && evidence.none { it.isConcrete }) {
            downgrades += "downgraded from PASSED: no concrete evidence was supplied"
            verdict = Verdict.INCONCLUSIVE
        }

        // 4. a failed verify must say what is missing
        var suggestion = judgement.suggestion
        if (item is CheckItem.Verify && verdict == Verdict.FAILED && suggestion.isNullOrBlank()) {
            downgrades += "downgraded from FAILED: a failed verify must carry an actionable " +
                "suggestion naming what is missing, and none was supplied"
            verdict = Verdict.INCONCLUSIVE
        }
        if (item is CheckItem.Assert && !suggestion.isNullOrBlank()) {
            downgrades += "dropped the repair suggestion: an assert describes the device, and " +
                "nothing should try to repair it"
            suggestion = null
        }

        return SealedJudgement(
            item = item,
            verdict = verdict,
            evidence = evidence,
            suggestion = suggestion,
            rationale = judgement.rationale,
            downgrades = downgrades
        )
    }

    /**
     * Repairs are capped. Once the budget is spent the failure stands as the result — the
     * alternative is an agent that relitigates a failed check until it runs out of steps.
     */
    fun mayRepair(item: CheckItem, repairsUsed: Int): Boolean =
        item is CheckItem.Verify && repairsUsed < item.repairBudget
}
