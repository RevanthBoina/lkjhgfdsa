package com.aniob.core.memory.history

/**
 * Plan-4.6: Two Scrub Edges over the context.
 *
 *   text edge at depth 1  — as soon as a newer observation exists, the older one loses its UI
 *                           element list, plan recitation and per-turn ephemeral blocks.
 *                           THIS IS A SAFETY PROPERTY: only the live observation carries an
 *                           indexed element list, so a stale [n] can never be picked as a target.
 *   image edge at depth 3 — (relaxed to 6 while occupancy is low) the screenshot is replaced in
 *                           place by its visual summary and NEVER restored.
 *
 * ## Why this is destructive rather than a recomputed view
 * A scrubber that re-derives the rendering from the original turns every call is not monotonic:
 * when occupancy drops after a compaction the image edge widens again and a screenshot that was
 * already replaced comes BACK. That breaks "never restored", and — worse — it rewrites bytes in
 * the middle of the prompt, so the append-only byte-stable prefix required by 5.1 and 4.8 is
 * destroyed and provider-side prompt caching cannot hit. Scrubbing therefore mutates the turn
 * once, in place, and can never be undone.
 */
class TwoEdgeContextScrubber(
    private val budget: HistoryBudget = HistoryBudget.DEFAULT
) {

    /**
     * A turn in history. Mutable and monotonic: fields only ever move from present to scrubbed.
     */
    class HistoryTurn(
        val stepNumber: Int,
        val stepId: String,
        val userPrompt: String,
        uiElementList: List<String>? = null,
        planRecitation: String? = null,
        ephemeralBlocks: List<String>? = null,
        screenshotRef: String? = null,
        visualSummary: String? = null,
        /** Only a turn whose action FAILED carries one (Plan-5.2). */
        val executionResult: String? = null,
        val tokenEstimate: Int = 0
    ) {
        var uiElementList: List<String>? = uiElementList; private set
        var planRecitation: String? = planRecitation; private set
        var ephemeralBlocks: List<String>? = ephemeralBlocks; private set
        var screenshotRef: String? = screenshotRef; private set
        var visualSummary: String? = visualSummary; private set

        var textScrubbed: Boolean = false; private set
        var imageScrubbed: Boolean = false; private set

        /** Plan-4.8: a turn past both edges is frozen; nothing may rewrite it again. */
        val isFrozen: Boolean get() = textScrubbed && imageScrubbed

        internal fun applyTextEdge() {
            if (textScrubbed) return
            uiElementList = null
            planRecitation = null
            // Plan-5.3: ephemerals are DELETED, not frozen. They never reach a chunk.
            ephemeralBlocks = null
            textScrubbed = true
        }

        internal fun applyImageEdge(fallbackSummary: String) {
            if (imageScrubbed) return
            if (visualSummary.isNullOrBlank()) visualSummary = fallbackSummary
            screenshotRef = null
            imageScrubbed = true
        }

        fun setVisualSummary(summary: String) {
            if (imageScrubbed) return   // never rewrite a frozen image slot
            visualSummary = summary
        }
    }

    data class ScrubOutcome(
        val plan: HistoryBudget.CompactionPlan,
        val textScrubbedSteps: List<Int>,
        val imageScrubbedSteps: List<Int>,
        val newlyFrozenSteps: List<Int>
    )

    /**
     * Applies both edges destructively. Returns which turns changed so the caller can freeze the
     * matching summary slots.
     *
     * @param turns     newest LAST
     * @param occupancy measured over the whole assembled prompt, not just these turns
     */
    fun scrub(
        turns: List<HistoryTurn>,
        occupancy: HistoryBudget.Occupancy
    ): ScrubOutcome {
        val plan = budget.plan(occupancy, turns.map { it.stepNumber })
        val n = turns.size
        val textScrubbed = mutableListOf<Int>()
        val imageScrubbed = mutableListOf<Int>()
        val newlyFrozen = mutableListOf<Int>()

        turns.forEachIndexed { index, turn ->
            val depth = (n - 1) - index          // depth 0 == live observation
            val wasFrozen = turn.isFrozen

            if (depth >= plan.textEdgeDepth && !turn.textScrubbed) {
                turn.applyTextEdge()
                textScrubbed += turn.stepNumber
            }
            if (depth >= plan.imageEdgeDepth && !turn.imageScrubbed) {
                turn.applyImageEdge(NO_SUMMARY_FALLBACK)
                imageScrubbed += turn.stepNumber
            }
            if (!wasFrozen && turn.isFrozen) newlyFrozen += turn.stepNumber
        }

        return ScrubOutcome(plan, textScrubbed, imageScrubbed, newlyFrozen)
    }

    companion object {
        /**
         * The text edge must ANNOUNCE itself. A scrubbed observation that simply has no element
         * list looks to the model like a screen with no elements, which invites it to invent an
         * index. Saying the list was withheld, and why, costs one line and removes the ambiguity.
         */
        const val ELEMENTS_WITHHELD_MARKER =
            "[elements withheld — only the live observation carries indexed elements; " +
                "do not target an index from this turn]"

        const val NO_SUMMARY_FALLBACK =
            "I did not record a visual summary for this step; the screenshot is no longer available."
    }
}
