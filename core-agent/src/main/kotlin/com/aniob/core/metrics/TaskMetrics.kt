package com.aniob.core.metrics

/**
 * Plan-8.5 — THE TWELVE METRICS.
 *
 * `AniobStatsScreen` currently shows totals: tasks run, steps taken, success rate. None of those
 * tell you whether a change helped, because none of them separate *did it work* from *was it
 * pleasant* from *was it honest*. `StepRecord.tokensUsed` is hardcoded to 0, so the one cost
 * number in the product is a literal zero that has never been measured.
 *
 * These twelve are chosen so that each one can move independently and each one has an owner in
 * Plans 4-7. A metric nobody can act on is decoration.
 */
data class TaskMetrics(
    // --- did it work
    /** 8.5.1 The task, judged by the user's goal, not by the agent's own report. */
    val succeeded: Boolean,
    /** 8.5.2 Steps to completion. Lower is better only when succeeded is true. */
    val steps: Int,
    /** 8.5.3 Wall clock, the number the user actually experiences. */
    val wallClockMs: Long,
    /** 8.5.4 Time the user spent waiting on a model rather than on the device. */
    val inferenceMs: Long,

    // --- was it honest  (Plans 6 and 7)
    /** 8.5.5 Claimed done while not done. The single most trust-destroying event. */
    val falseSuccess: Boolean,
    /** 8.5.6 Verdicts that could not be decided. High means the probe table is too thin. */
    val inconclusiveVerdicts: Int,
    /** 8.5.7 Fallbacks announced. Zero with a degraded run means 7.9 regressed. */
    val degradationsAnnounced: Int,

    // --- was it pleasant  (Plan 7.7, the moat)
    /** 8.5.8 Questions asked before the first action. Target: 0 or 1. */
    val questionsBeforeStarting: Int,
    /** 8.5.9 Questions asked in total. */
    val questionsTotal: Int,
    /** 8.5.10 Failed because it guessed instead of asking. Under-asking is also a failure. */
    val failedForLackOfQuestion: Boolean,

    // --- did it cost  (Plans 4 and 5)
    /** 8.5.11 Real tokens. Replaces the hardcoded 0 in StepRecord. */
    val tokensUsed: Int,
    /** 8.5.12 Prompt bytes served from a stable prefix. Plan-5's whole point. */
    val cachedPrefixBytes: Int,

    val taskId: String = "",
    val runIndex: Int = 0
) {
    /** The honest headline. A false success is worse than a failure, so it is not a success. */
    val trustworthySuccess: Boolean get() = succeeded && !falseSuccess

    fun asMap(): Map<String, Any> = linkedMapOf(
        "task_id" to taskId,
        "run_index" to runIndex,
        "succeeded" to succeeded,
        "trustworthy_success" to trustworthySuccess,
        "steps" to steps,
        "wall_clock_ms" to wallClockMs,
        "inference_ms" to inferenceMs,
        "false_success" to falseSuccess,
        "inconclusive_verdicts" to inconclusiveVerdicts,
        "degradations_announced" to degradationsAnnounced,
        "questions_before_starting" to questionsBeforeStarting,
        "questions_total" to questionsTotal,
        "failed_for_lack_of_question" to failedForLackOfQuestion,
        "tokens_used" to tokensUsed,
        "cached_prefix_bytes" to cachedPrefixBytes
    )

    companion object {
        /** Display names for `AniobStatsScreen`. Twelve rows, in this order. */
        val DISPLAY_ORDER: List<Pair<String, String>> = listOf(
            "trustworthy_success" to "Worked, and told the truth about it",
            "steps" to "Steps",
            "wall_clock_ms" to "Time you waited",
            "inference_ms" to "Of that, thinking",
            "false_success" to "Said done when it was not",
            "inconclusive_verdicts" to "Could not tell",
            "degradations_announced" to "Workarounds it told you about",
            "questions_before_starting" to "Questions before starting",
            "questions_total" to "Questions in total",
            "failed_for_lack_of_question" to "Guessed when it should have asked",
            "tokens_used" to "Tokens",
            "cached_prefix_bytes" to "Prompt bytes reused"
        )
    }
}
