package com.aniob.core.memory.history

/**
 * Plan-4.5: Three-Band Chunks for older spans (3..12 steps, ~2000 source tokens, 8 chunks max).
 *
 *   band 1 - segment synopsis: doing / did / effect + structured fields
 *   band 2 - interval narrative covering EVERY step with no gaps or overlaps
 *   band 3 - the mechanical per-step action ledger, assembled in CODE, never by the model
 *
 * ## Why band 2 is a list, not a paragraph
 * The spec requires "the intervals must exactly cover the step range" to be machine-checked and
 * regenerated on violation. If band 2 is a free-form String that property is not merely unchecked,
 * it is structurally UNCHECKABLE — there is nothing in the data to compare against the range.
 * Modelling intervals explicitly is what turns the rule into a test.
 */
data class ThreeBandChunk(
    val chunkIndex: Int,
    val startStep: Int,
    val endStep: Int,
    val synopsis: SegmentSynopsis,
    val intervals: List<Interval>,
    val actionLedger: List<LedgerEntry>
) {
    init {
        require(endStep >= startStep) { "endStep ($endStep) must be >= startStep ($startStep)" }
    }

    /** One merged run of homogeneous steps. [from]..[to] inclusive. */
    data class Interval(val from: Int, val to: Int, val narrative: String) {
        init { require(to >= from) { "interval $from..$to is inverted" } }
        val length: Int get() = to - from + 1
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
        val exitState: String,
        /** Note keys written during this segment; every one must appear in [effect]. */
        val noteKeysWritten: List<String> = emptyList()
    )

    data class LedgerEntry(
        val stepNumber: Int,
        val actionType: String,
        val targetDescription: String,
        val recordedResult: String
    )

    val stepCount: Int get() = endStep - startStep + 1

    /** Band 2 rendered for the prompt. */
    fun renderIntervals(): String = intervals.joinToString("\n") {
        if (it.length == 1) "Step ${it.from}: ${it.narrative}"
        else "Steps ${it.from}-${it.to}: ${it.narrative}"
    }

    companion object {
        /**
         * Coordinates AND stale element indices. `[4]` is the exact token the text scrub edge
         * exists to eliminate; letting it survive inside a chunk reintroduces the hazard that
         * Plan-4.6 calls a safety property.
         */
        private val COORD_OR_INDEX = Regex(
            """\(\s*\d+\s*,\s*\d+\s*\)""" +              // (880, 1210)
                """|\[\s*\d+\s*(,\s*\d+\s*)?\]""" +      // [4]  or  [880, 1210]
                """|\b\d+\s*px\b""" +                    // 1210px
                """|\b[xy]\s*[=:]\s*-?\d+""",            // x=880  y: 1210
            RegexOption.IGNORE_CASE
        )

        /**
         * Machine-checks the chunk. A non-empty result means REGENERATE — do not ship the chunk.
         */
        fun validateChunk(
            chunk: ThreeBandChunk,
            budget: HistoryBudget = HistoryBudget.DEFAULT
        ): List<String> = buildList {

            // --- size ---
            if (chunk.stepCount < budget.chunkMinSteps) {
                add("Chunk spans ${chunk.stepCount} step(s); minimum is ${budget.chunkMinSteps}.")
            }
            if (chunk.stepCount > budget.chunkMaxSteps) {
                add("Chunk spans ${chunk.stepCount} step(s); maximum is ${budget.chunkMaxSteps}.")
            }

            // --- band 1 + band 2 neutrality ---
            fun neutral(label: String, text: String) {
                NeutralWordingContract.validate(text).violations.forEach { add("$label: $it") }
            }
            neutral("synopsis.doing", chunk.synopsis.doing)
            neutral("synopsis.did", chunk.synopsis.did)
            neutral("synopsis.effect", chunk.synopsis.effect)
            chunk.intervals.forEach { neutral("interval ${it.from}-${it.to}", it.narrative) }

            // --- every note key written must appear in effect ---
            chunk.synopsis.noteKeysWritten.forEach { key ->
                if (!chunk.synopsis.effect.contains(key)) {
                    add("Note key '$key' was written during this segment but does not appear in `effect`.")
                }
            }

            // --- band 2 carries no coordinates and no stale indices ---
            chunk.intervals.forEach { iv ->
                COORD_OR_INDEX.find(iv.narrative)?.let {
                    add("Interval ${iv.from}-${iv.to} contains a coordinate or element index ('${it.value}'); band 2 must carry neither.")
                }
            }

            // --- band 2 intervals exactly cover the range, no gaps, no overlaps ---
            addAll(coverageViolations(chunk))

            // --- band 3 covers every step ---
            val recorded = chunk.actionLedger.map { it.stepNumber }.toSet()
            for (step in chunk.startStep..chunk.endStep) {
                if (step !in recorded) {
                    add("Action ledger missing entry for step $step in range [${chunk.startStep}..${chunk.endStep}].")
                }
            }
        }

        /** "covering EVERY step with no gaps or overlaps" — now an actual assertion. */
        private fun coverageViolations(chunk: ThreeBandChunk): List<String> = buildList {
            if (chunk.intervals.isEmpty()) {
                add("Band 2 has no intervals; it must cover steps ${chunk.startStep}..${chunk.endStep}.")
                return@buildList
            }
            val sorted = chunk.intervals.sortedBy { it.from }
            if (sorted.first().from != chunk.startStep) {
                add("Band 2 starts at step ${sorted.first().from}; the chunk starts at ${chunk.startStep}.")
            }
            if (sorted.last().to != chunk.endStep) {
                add("Band 2 ends at step ${sorted.last().to}; the chunk ends at ${chunk.endStep}.")
            }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                if (cur.from > prev.to + 1) add("Band 2 gap between step ${prev.to} and step ${cur.from}.")
                if (cur.from <= prev.to) add("Band 2 overlap: interval ${cur.from}-${cur.to} re-covers step ${cur.from}.")
            }
        }

        /** Band 3 is assembled strictly in code, never by a model. */
        fun assembleLedgerInCode(entries: List<LedgerEntry>): List<LedgerEntry> =
            entries.sortedBy { it.stepNumber }

        /**
         * "Target one third of the source length — achieved by not repeating band 3 and by
         * merging runs, NEVER by abstracting away concrete facts." Reported, not enforced by
         * truncation, because truncating is exactly the failure mode the sentence forbids.
         */
        fun compressionRatio(chunk: ThreeBandChunk, sourceTokens: Int, estimate: (String) -> Int): Double {
            if (sourceTokens <= 0) return 0.0
            val produced = estimate(chunk.synopsis.doing) + estimate(chunk.synopsis.did) +
                estimate(chunk.synopsis.effect) + chunk.intervals.sumOf { estimate(it.narrative) }
            return produced.toDouble() / sourceTokens.toDouble()
        }
    }
}
