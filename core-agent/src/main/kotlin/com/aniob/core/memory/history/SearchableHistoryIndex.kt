package com.aniob.core.memory.history

import java.util.concurrent.ConcurrentHashMap

/**
 * Plan-4.7: Searchable History Index.
 *
 * Compressed spans stay recallable, which is what lets the turn cap be removed entirely:
 * history is bounded by compression, not by a step limit.
 *
 * Thread-safe because entries are written from the summary pool (2 threads) while the loop reads.
 * Recall is CAPPED — an uncapped search re-inflates the very context the scrub edges just freed.
 */
class SearchableHistoryIndex {

    data class IndexedStep(
        val stepNumber: Int,
        val fullContent: String,
        val screenshotPath: String?,
        val tokenCount: Int
    )

    data class RecallResult(
        val stepNumbers: List<Int>,
        val totalMatches: Int,
        val truncated: Boolean
    )

    private val steps = ConcurrentHashMap<Int, IndexedStep>()

    fun indexStep(stepNumber: Int, content: String, screenshotPath: String? = null, tokenCount: Int = 500) {
        steps[stepNumber] = IndexedStep(stepNumber, content, screenshotPath, tokenCount)
    }

    fun size(): Int = steps.size

    /**
     * Capped keyword / step-range lookup. Returns the LATEST matches, because when history is
     * long the recent occurrence of a term is almost always the relevant one.
     */
    fun recall(
        keyword: String? = null,
        stepRange: IntRange? = null,
        maxResults: Int = MAX_RECALL_RESULTS,
        maxTokens: Int = MAX_RECALL_TOKENS
    ): RecallResult {
        val query = keyword?.trim()?.lowercase()
        val all = steps.values
            .filter { step ->
                val inRange = stepRange == null || step.stepNumber in stepRange
                val matches = query.isNullOrEmpty() || step.fullContent.lowercase().contains(query)
                inRange && matches
            }
            .sortedByDescending { it.stepNumber }

        val picked = mutableListOf<Int>()
        var tokens = 0
        for (s in all) {
            if (picked.size >= maxResults) break
            if (tokens + s.tokenCount > maxTokens && picked.isNotEmpty()) break
            picked += s.stepNumber
            tokens += s.tokenCount
        }
        return RecallResult(
            stepNumbers = picked.sorted(),
            totalMatches = all.size,
            truncated = all.size > picked.size
        )
    }

    /** Backwards-compatible uncapped-shape accessor; prefer [recall]. */
    fun search(keyword: String? = null, stepRange: IntRange? = null): List<Int> =
        recall(keyword, stepRange).stepNumbers

    /**
     * Full-step replay, capped at 5 steps / 12k tokens.
     * The cap counts steps that actually EXIST — a requested id that was never indexed must not
     * consume a slot, or a stale reference silently shrinks the replay.
     */
    fun replaySteps(
        stepNumbers: List<Int>,
        maxSteps: Int = MAX_REPLAY_STEPS,
        maxTokens: Int = MAX_REPLAY_TOKENS
    ): List<IndexedStep> {
        val selected = mutableListOf<IndexedStep>()
        var tokens = 0
        for (n in stepNumbers) {
            if (selected.size >= maxSteps) break
            val step = steps[n] ?: continue
            if (tokens + step.tokenCount > maxTokens && selected.isNotEmpty()) break
            selected += step
            tokens += step.tokenCount
        }
        return selected
    }

    fun getScreenshot(stepNumber: Int): String? = steps[stepNumber]?.screenshotPath

    fun clear() = steps.clear()

    companion object {
        const val MAX_RECALL_RESULTS = 5
        const val MAX_RECALL_TOKENS = 2_000
        const val MAX_REPLAY_STEPS = 5
        const val MAX_REPLAY_TOKENS = 12_000
    }
}
