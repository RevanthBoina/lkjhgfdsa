package com.aniob.core.memory.history

/**
 * Plan-4.7: Searchable History Index.
 * Compressed spans stay recallable:
 * - Keyword and step-range lookup returning step numbers
 * - Full-step replay (capped at 5 steps / 12k tokens)
 * - Stored screenshot fetch reference
 */
class SearchableHistoryIndex {

    data class IndexedStep(
        val stepNumber: Int,
        val fullContent: String,
        val screenshotPath: String?,
        val tokenCount: Int
    )

    private val steps = mutableMapOf<Int, IndexedStep>()

    fun indexStep(stepNumber: Int, content: String, screenshotPath: String? = null, tokenCount: Int = 500) {
        steps[stepNumber] = IndexedStep(
            stepNumber = stepNumber,
            fullContent = content,
            screenshotPath = screenshotPath,
            tokenCount = tokenCount
        )
    }

    /**
     * Finds matching step numbers by keyword or step range.
     */
    fun search(keyword: String? = null, stepRange: IntRange? = null): List<Int> {
        val query = keyword?.trim()?.lowercase()
        return steps.values
            .filter { step ->
                val inRange = stepRange == null || step.stepNumber in stepRange
                val matchesKeyword = query.isNullOrEmpty() || step.fullContent.lowercase().contains(query)
                inRange && matchesKeyword
            }
            .map { it.stepNumber }
            .sorted()
    }

    /**
     * Replays steps up to cap (5 steps, 12k tokens max).
     */
    fun replaySteps(stepNumbers: List<Int>, maxSteps: Int = 5, maxTokens: Int = 12_000): List<IndexedStep> {
        val selected = mutableListOf<IndexedStep>()
        var tokensAccumulated = 0

        for (stepNum in stepNumbers.take(maxSteps)) {
            val step = steps[stepNum] ?: continue
            if (tokensAccumulated + step.tokenCount > maxTokens) break
            selected.add(step)
            tokensAccumulated += step.tokenCount
        }
        return selected
    }

    fun getScreenshot(stepNumber: Int): String? = steps[stepNumber]?.screenshotPath
}
