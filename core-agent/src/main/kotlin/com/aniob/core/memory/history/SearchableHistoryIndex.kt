package com.aniob.core.memory.history

import java.util.Comparator
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Plan-4.7: Searchable History Index.
 *
 * Compressed spans stay recallable, which is what lets the turn cap be removed entirely:
 * history is bounded by compression, not by a step limit.
 *
 * Thread-safe because entries are written from the summary pool (2 threads) while the loop reads.
 * Backed by a descending [ConcurrentSkipListMap] with pre-lowercased content so [recall] executes
 * in a single zero-allocation descending pass without per-query string lowercasing or sorting.
 */
class SearchableHistoryIndex {

    data class IndexedStep(
        val stepNumber: Int,
        val fullContent: String,
        val screenshotPath: String?,
        val tokenCount: Int,
        internal val lowerContent: String = fullContent.lowercase()
    )

    data class RecallResult(
        val stepNumbers: List<Int>,
        val totalMatches: Int,
        val truncated: Boolean
    )

    private val steps = ConcurrentSkipListMap<Int, IndexedStep>(Comparator.reverseOrder())

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
        val picked = mutableListOf<Int>()
        var tokens = 0
        var totalMatches = 0

        for (step in steps.values) {
            val inRange = stepRange == null || step.stepNumber in stepRange
            if (!inRange) continue
            val matches = query.isNullOrEmpty() || step.lowerContent.contains(query)
            if (!matches) continue

            totalMatches++
            if (picked.size < maxResults && (tokens + step.tokenCount <= maxTokens || picked.isEmpty())) {
                picked += step.stepNumber
                tokens += step.tokenCount
            }
        }

        return RecallResult(
            stepNumbers = picked.sorted(),
            totalMatches = totalMatches,
            truncated = totalMatches > picked.size
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
