package com.aniob.core.verification

/**
 * Plan-6.2 / 6.3 / 6.4 — the verdict vocabulary.
 *
 * `AniobStatsScreen` currently displays a "Task Success Rate" with no external ground truth behind
 * it. That number is fiction. Everything here exists so it can stop being fiction.
 */

/**
 * THREE-VALUED. Absence of evidence is INCONCLUSIVE, never failed.
 *
 * "This single rule is the difference between a verifier you can trust and one you learn to
 * ignore." A two-valued verifier has to guess when it cannot see, and it will guess `failed`,
 * and within a week nobody reads its output.
 */
enum class Verdict { PASSED, FAILED, INCONCLUSIVE }

enum class EvidenceKind { PROBE_OUTPUT, NOTE_EXCERPT, SCREEN_OBSERVATION, RECORDED_STEP }

/** Concrete evidence. A verdict without one of these is not a verdict. */
data class Evidence(
    val kind: EvidenceKind,
    /** e.g. "probe_alarms", "step 14", "note:order_id" */
    val source: String,
    /** The actual text observed. Not a paraphrase of it. */
    val excerpt: String,
    /** Step at which the evidence was recorded, for anchoring (6.4). */
    val atStep: Int? = null
) {
    val isConcrete: Boolean get() = source.isNotBlank() && excerpt.isNotBlank()
}

/**
 * Two kinds of check with DIFFERENT semantics. Conflating them is how an execution bug gets
 * reported as a device defect.
 */
sealed interface CheckItem {
    /** Quoted verbatim in the verdict; never paraphrased. */
    val text: String

    /**
     * Completion acceptance criteria. A failure can send the step back for repair, so it MUST
     * carry an actionable suggestion saying what is missing.
     */
    data class Verify(override val text: String, val repairBudget: Int = DEFAULT_REPAIR_BUDGET) : CheckItem

    /**
     * An expectation about the DEVICE itself. A failure here is a legitimate, valuable result and
     * nothing should try to repair it — but only once we know the triggering action actually ran.
     */
    data class Assert(
        override val text: String,
        /** The action that was supposed to cause the asserted state. */
        val triggeringActionDescription: String,
        /** True when the expectation is about a toast/dialog/momentary state or an ordering. */
        val isTransientOrOrdering: Boolean = false
    ) : CheckItem

    companion object {
        /** "Cap repairs (2) — once the budget is spent the failure stands as the result." */
        const val DEFAULT_REPAIR_BUDGET = 2
    }
}

/** A raw judgement as produced by the checker, before the invariants are applied. */
data class Judgement(
    val item: CheckItem,
    val verdict: Verdict,
    val evidence: List<Evidence> = emptyList(),
    /** Required on a failed `verify:`; meaningless on an `assert:`. */
    val suggestion: String? = null,
    val rationale: String = ""
)

/** A judgement that has survived [CheckerInvariants]. Only these may be released. */
data class SealedJudgement(
    val item: CheckItem,
    val verdict: Verdict,
    val evidence: List<Evidence>,
    val suggestion: String?,
    val rationale: String,
    /** Non-empty when the raw verdict was changed, and why. Always shown, never hidden. */
    val downgrades: List<String>
) {
    /** The item, quoted verbatim, exactly as written. Never paraphrase a check item. */
    fun quotedItem(): String = "\"${item.text}\""

    fun render(): String = buildString {
        appendLine("${verdict.name}: ${quotedItem()}")
        if (rationale.isNotBlank()) appendLine("  reason: $rationale")
        evidence.forEach { appendLine("  evidence [${it.kind.name.lowercase()} @ ${it.source}]: ${it.excerpt}") }
        suggestion?.let { appendLine("  suggestion: $it") }
        downgrades.forEach { appendLine("  adjusted: $it") }
    }.trimEnd()
}
