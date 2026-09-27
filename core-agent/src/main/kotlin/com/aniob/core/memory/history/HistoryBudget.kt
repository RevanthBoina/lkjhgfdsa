package com.aniob.core.memory.history

/**
 * Plan-4.6: the occupancy ladder that was specified but never implemented.
 *
 * "Occupancy gates against a context budget (~80k): start replacing at 0.35, soft 0.7, hard 0.9,
 * always keeping >= 5 active steps."
 *
 * Two things this fixes:
 *  1. Occupancy is measured against the WHOLE prompt (system prefix + chunks + notes + tool
 *     schemas + active turns), not just the turns handed to a scrubber. Driving policy off a
 *     partial denominator means the policy fires at the wrong time, in both directions.
 *  2. It emits an explicit [CompactionPlan], which is what gives ThreeBandChunk a producer.
 *     Without a pressure ladder nothing ever decides to chunk, so the chunk type is unreachable.
 */
data class HistoryBudget(
    val totalTokens: Int = 80_000,
    val startRatio: Double = 0.35,
    val softRatio: Double = 0.70,
    val hardRatio: Double = 0.90,
    val minActiveSteps: Int = 5,
    val textScrubDepth: Int = 1,
    val imageScrubDepth: Int = 3,
    val relaxedImageScrubDepth: Int = 6,
    val chunkMinSteps: Int = 3,
    val chunkMaxSteps: Int = 12,
    val chunkTargetSourceTokens: Int = 2_000,
    val maxChunks: Int = 8
) {
    init {
        require(startRatio < softRatio && softRatio < hardRatio) { "ratios must ascend" }
        require(minActiveSteps >= 1) { "minActiveSteps must be >= 1" }
        require(chunkMinSteps in 1..chunkMaxSteps) { "chunkMinSteps must be <= chunkMaxSteps" }
    }

    enum class Pressure { LOW, START, SOFT, HARD }

    /**
     * The whole prompt, not just the turns. Every field here is a real consumer of the budget.
     */
    data class Occupancy(
        val systemPrefixTokens: Int,
        val chunkTokens: Int,
        val noteTokens: Int,
        val toolSchemaTokens: Int,
        val activeTurnTokens: Int
    ) {
        val used: Int get() = systemPrefixTokens + chunkTokens + noteTokens + toolSchemaTokens + activeTurnTokens
    }

    data class CompactionPlan(
        val pressure: Pressure,
        val ratio: Double,
        /** Turns at this depth or deeper lose their element list, plan recitation and ephemerals. */
        val textEdgeDepth: Int,
        /** Turns at this depth or deeper have the screenshot replaced by its visual summary. */
        val imageEdgeDepth: Int,
        /** Steps to fold into a three-band chunk, or null when nothing should be chunked yet. */
        val chunkRange: IntRange?,
        val reason: String
    )

    fun ratioOf(occupancy: Occupancy): Double = occupancy.used.toDouble() / totalTokens.toDouble()

    fun pressureOf(ratio: Double): Pressure = when {
        ratio >= hardRatio -> Pressure.HARD
        ratio >= softRatio -> Pressure.SOFT
        ratio >= startRatio -> Pressure.START
        else -> Pressure.LOW
    }

    /**
     * @param occupancy   measured over the assembled prompt
     * @param activeSteps step numbers currently rendered in full, ascending
     */
    fun plan(occupancy: Occupancy, activeSteps: List<Int>): CompactionPlan {
        val ratio = ratioOf(occupancy)
        val pressure = pressureOf(ratio)

        // Image edge relaxes to 6 only while the context is genuinely cheap.
        val imageEdge = if (pressure == Pressure.LOW) relaxedImageScrubDepth else imageScrubDepth

        val chunkRange = chunkRangeFor(pressure, activeSteps)

        val reason = when (pressure) {
            Pressure.LOW -> "occupancy %.2f < start %.2f: image edge relaxed to %d".format(ratio, startRatio, imageEdge)
            Pressure.START -> "occupancy %.2f >= start %.2f: image edge tightened to %d".format(ratio, startRatio, imageEdge)
            Pressure.SOFT -> "occupancy %.2f >= soft %.2f: folding oldest span into a chunk".format(ratio, softRatio)
            Pressure.HARD -> "occupancy %.2f >= hard %.2f: folding aggressively, keeping only %d active steps"
                .format(ratio, hardRatio, minActiveSteps)
        }

        return CompactionPlan(pressure, ratio, textScrubDepth, imageEdge, chunkRange, reason)
    }

    /**
     * Picks the oldest span to fold. Never touches the [minActiveSteps] most recent steps, and
     * never emits a span shorter than [chunkMinSteps] — a 1-step "chunk" costs more in banding
     * overhead than the step it replaces.
     */
    private fun chunkRangeFor(pressure: Pressure, activeSteps: List<Int>): IntRange? {
        if (pressure == Pressure.LOW || pressure == Pressure.START) return null
        val sorted = activeSteps.sorted()
        val foldable = sorted.size - minActiveSteps
        if (foldable < chunkMinSteps) return null
        val take = if (pressure == Pressure.HARD) minOf(foldable, chunkMaxSteps) else minOf(foldable, chunkMinSteps)
        if (take < chunkMinSteps) return null
        return sorted[0]..sorted[take - 1]
    }

    companion object {
        val DEFAULT = HistoryBudget()
    }
}
