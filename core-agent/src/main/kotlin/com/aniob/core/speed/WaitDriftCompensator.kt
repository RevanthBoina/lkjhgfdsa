package com.aniob.core.speed

import kotlin.math.roundToLong

/**
 * Plan-5.7 — WAIT DRIFT COMPENSATION.
 *
 * Every turn in a polling loop adds inference and transport time on top of the explicit wait.
 * A loop asked to check "every 5 seconds" that spends 2.4s per turn in the model actually checks
 * every 7.4s, then every 7.4s again — so by the tenth poll it is 24 seconds behind where the user
 * thinks it is. For anything time-sensitive (a countdown, an OTP window, a live price) that drift
 * is the difference between catching the state and missing it.
 *
 * Fix: measure the per-turn overhead and SHORTEN the explicit wait by it, so checks land on the
 * intended cadence instead of drifting later every cycle.
 */
class WaitDriftCompensator(
    private val minWaitMs: Long = 200L,
    private val maxWaitMs: Long = 5_000L,
    /** EWMA weight for the newest sample. Higher reacts faster, lower is steadier. */
    private val alpha: Double = 0.4
) {
    private var overheadMs: Double = 0.0
    private var samples: Int = 0

    val measuredOverheadMs: Long get() = overheadMs.roundToLong()
    val sampleCount: Int get() = samples

    /**
     * @param intendedWaitMs what we asked the loop to wait
     * @param actualElapsedMs wall-clock from the start of one check to the start of the next
     */
    fun observe(intendedWaitMs: Long, actualElapsedMs: Long) {
        val overhead = (actualElapsedMs - intendedWaitMs).coerceAtLeast(0L).toDouble()
        overheadMs = if (samples == 0) overhead else (alpha * overhead) + ((1 - alpha) * overheadMs)
        samples++
    }

    /**
     * The wait to actually request so the NEXT check lands [intendedCadenceMs] after the last one.
     * Clamped, and never negative — if overhead already exceeds the cadence the loop is running as
     * fast as it can and [isSaturated] tells the caller to stop pretending otherwise.
     */
    fun compensate(intendedCadenceMs: Long): Long =
        (intendedCadenceMs - measuredOverheadMs).coerceIn(minWaitMs, maxWaitMs)

    /** True when inference alone already costs more than the requested cadence. */
    fun isSaturated(intendedCadenceMs: Long): Boolean = measuredOverheadMs >= intendedCadenceMs

    /**
     * Honest user-facing line for the timeline when the cadence cannot be met. Plan-7.9: a
     * degradation the user cannot see is indistinguishable from a bug.
     */
    fun saturationNotice(intendedCadenceMs: Long): String? =
        if (!isSaturated(intendedCadenceMs)) null
        else "I am checking about every ${measuredOverheadMs / 1000.0} s instead of every " +
            "${intendedCadenceMs / 1000.0} s, because each check takes that long to think through."

    fun reset() {
        overheadMs = 0.0
        samples = 0
    }
}
