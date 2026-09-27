package com.aniob.core.memory.history

/**
 * Plan-4.5: Three-Band Chunks for older spans (3 to 12 steps, ~2000 source tokens, 8 chunks max).
 * Band 1: Segment synopsis (doing/did/effect + structured fields)
 * Band 2: Interval narrative covering every step with no gaps or overlaps (no coordinates allowed!)
 * Band 3: Mechanical per-step action ledger, assembled strictly in code
 */
data class ThreeBandChunk(
    val chunkIndex: Int,
    val startStep: Int,
    val endStep: Int,
    // Band 1
    val synopsis: SegmentSynopsis,
    // Band 2
    val intervalNarrative: String,
    // Band 3
    val actionLedger: List<LedgerEntry>
) {
    init {
        require(endStep >= startStep) { "endStep ($endStep) must be >= startStep ($startStep)" }
        val stepCount = endStep - startStep + 1
        require(stepCount in 1..12) { "Chunk size must be between 1 and 12 steps (got $stepCount)" }
    }

    data class SegmentSynopsis(
        val doing: String,
        val did: String,
        val effect: String,
        val verifiedFacts: List<String> = emptyList(),
        val unresolved: List<String> = emptyList(),
        val failedPaths: List<String> = emptyList(),
        val importantEntities: List<String> = emptyList(),
        val entryState: String,
        val exitState: String
    )

    data class LedgerEntry(
        val stepNumber: Int,
        val actionType: String,
        val targetDescription: String,
        val recordedResult: String
    )

    companion object {
        /**
         * Machine-checks the chunk output:
         * - Neutral wording contract holds
         * - Band 2 contains NO coordinates
         * - Intervals cover the step range
         */
        fun validateChunk(chunk: ThreeBandChunk): List<String> = buildList {
            // Check neutrality
            val n1 = NeutralWordingContract.validate(chunk.synopsis.doing)
            if (!n1.isValid) addAll(n1.violations)
            val n2 = NeutralWordingContract.validate(chunk.synopsis.did)
            if (!n2.isValid) addAll(n2.violations)
            val n3 = NeutralWordingContract.validate(chunk.synopsis.effect)
            if (!n3.isValid) addAll(n3.violations)
            val n4 = NeutralWordingContract.validate(chunk.intervalNarrative)
            if (!n4.isValid) addAll(n4.violations)

            // Band 2 must contain no coordinates (e.g. (x, y) or [x, y] or \d+px)
            val coordRegex = """(\(\s*\d+\s*,\s*\d+\s*\)|\[\s*\d+\s*,\s*\d+\s*\]|\b\d+px\b)""".toRegex()
            if (coordRegex.containsMatchIn(chunk.intervalNarrative)) {
                add("Band 2 interval narrative must contain NO coordinates (e.g. (x, y) or px values).")
            }

            // Ledger must cover all steps
            val recordedSteps = chunk.actionLedger.map { it.stepNumber }.toSet()
            for (step in chunk.startStep..chunk.endStep) {
                if (!recordedSteps.contains(step)) {
                    add("Action ledger missing entry for step $step in range [${chunk.startStep}..${chunk.endStep}].")
                }
            }
        }

        /**
         * Assembles Band 3 strictly in code, never by LLM.
         */
        fun assembleLedgerInCode(entries: List<LedgerEntry>): List<LedgerEntry> {
            return entries.sortedBy { it.stepNumber }
        }
    }
}
