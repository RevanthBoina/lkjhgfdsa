package com.aniob.core.memory.history

/**
 * Plan-4.6: Two Scrub Edges over the context.
 *
 * Text Edge at depth 1:
 * Older observations lose their UI element list, plan recitation, and ephemeral blocks.
 * This is a crucial safety property: prevents stale [n] indices from being picked up as targets.
 *
 * Image Edge at depth 3 (relaxed to 6 when occupancy is low):
 * Replaces the raw screenshot with its visual transition summary.
 */
class TwoEdgeContextScrubber(
    private val totalContextBudgetTokens: Int = 80_000
) {
    data class HistoricalTurn(
        val stepNumber: Int,
        val userPrompt: String,
        val rawUiElementList: List<String>?,
        val planRecitation: String?,
        val ephemeralBlocks: List<String>?,
        val screenshotRef: String?,
        val visualSummary: String?,
        val tokenEstimate: Int
    )

    data class ScrubbedTurn(
        val stepNumber: Int,
        val userPrompt: String,
        val uiElementList: List<String>?,
        val planRecitation: String?,
        val ephemeralBlocks: List<String>?,
        val screenshotRef: String?,
        val visualSummary: String?
    )

    fun calculateOccupancy(totalUsedTokens: Int): Double {
        return totalUsedTokens.toDouble() / totalContextBudgetTokens.toDouble()
    }

    /**
     * Scrubs a history of turns according to depths and context occupancy.
     * Guaranteed: at least 5 active steps preserved.
     */
    fun scrub(turns: List<HistoricalTurn>): List<ScrubbedTurn> {
        val n = turns.size
        val totalTokens = turns.sumOf { it.tokenEstimate }
        val occupancy = calculateOccupancy(totalTokens)

        // Image edge depth: depth 3 by default, relaxed to 6 when occupancy < 0.35
        val imageEdgeDepth = if (occupancy < 0.35) 6 else 3

        return turns.mapIndexed { index, turn ->
            val depth = (n - 1) - index // depth 0 is latest turn

            // Text edge at depth 1 (depth >= 1): scrub UI elements, plan recitation, and ephemeral blocks
            val isBeyondTextEdge = depth >= 1
            val scrubbedElements = if (isBeyondTextEdge) null else turn.rawUiElementList
            val scrubbedPlan = if (isBeyondTextEdge) null else turn.planRecitation
            val scrubbedEphemeral = if (isBeyondTextEdge) null else turn.ephemeralBlocks

            // Image edge: replace screenshot with visual summary
            val isBeyondImageEdge = depth >= imageEdgeDepth
            val scrubbedScreenshot = if (isBeyondImageEdge) null else turn.screenshotRef
            val visualSummary = if (isBeyondImageEdge) {
                turn.visualSummary ?: "Observed visual state change without recorded summary."
            } else {
                turn.visualSummary
            }

            ScrubbedTurn(
                stepNumber = turn.stepNumber,
                userPrompt = turn.userPrompt,
                uiElementList = scrubbedElements,
                planRecitation = scrubbedPlan,
                ephemeralBlocks = scrubbedEphemeral,
                screenshotRef = scrubbedScreenshot,
                visualSummary = visualSummary
            )
        }
    }
}
