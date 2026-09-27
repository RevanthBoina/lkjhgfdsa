package com.aniob.core.speed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plan-5.5 — SPECULATIVE PERCEPTION.
 *
 * While the model is generating, probe the UI in parallel and inject what you learn into the turn.
 * Published measurement on this technique: **23% fewer reasoning steps and 23% lower end-to-end
 * latency, with success maintained or up 5%** — the rare change that buys accuracy and speed at
 * once, because the latency it removes is latency that was causing wrong taps.
 *
 * It is only safe with a rollback path. The reference implementation measured 96.8% rollback
 * success over 1,200 probes at 38ms mean overhead, with non-deterministic animations and
 * permission dialogs as the two failure modes — both handled by a full restart of the step rather
 * than by trying to reconcile.
 *
 * This class owns the concurrency and the accounting. The probe itself is injected, because
 * anything that touches the device lives in `:app`.
 */
class SpeculativePerception<P>(
    /** Read-only probe executed CONCURRENTLY with generation. Must not mutate the device. */
    private val probe: suspend () -> P,
    /**
     * Returns true when the screen is still the one the probe observed. False means the UI moved
     * (animation, permission dialog) and the speculation must be discarded.
     */
    private val stillValid: suspend (P) -> Boolean,
    /** Restores pre-probe state. Returns false when restoration is not possible. */
    private val rollback: suspend (P) -> Boolean,
    private val probeTimeoutMs: Long = 1_500L
) {

    sealed interface Result<out T, out P> {
        /** Generation finished and the probe is still valid: inject [probeResult] into the turn. */
        data class Injected<T, P>(val generated: T, val probeResult: P, val probeMs: Long) : Result<T, P>

        /** Generation finished but the screen moved. The step must restart from a fresh capture. */
        data class Discarded<T>(val generated: T, val reason: String, val rolledBack: Boolean) : Result<T, Nothing>

        /** The probe itself did not return in time. Generation is still usable, unaugmented. */
        data class NoProbe<T>(val generated: T, val reason: String) : Result<T, Nothing>
    }

    data class Stats(
        val attempts: Int,
        val injected: Int,
        val discarded: Int,
        val rollbackFailures: Int,
        val probeTimeouts: Int,
        val meanProbeMs: Long
    ) {
        val injectionRate: Double get() = if (attempts == 0) 0.0 else injected.toDouble() / attempts
        val rollbackSuccessRate: Double get() =
            if (discarded == 0) 1.0 else (discarded - rollbackFailures).toDouble() / discarded
    }

    private var attempts = 0
    private var injected = 0
    private var discarded = 0
    private var rollbackFailures = 0
    private var probeTimeouts = 0
    private var totalProbeMs = 0L
    private var probeSamples = 0

    fun stats(): Stats = Stats(
        attempts, injected, discarded, rollbackFailures, probeTimeouts,
        if (probeSamples == 0) 0 else totalProbeMs / probeSamples
    )

    /**
     * Runs [generate] and [probe] concurrently and reconciles them.
     *
     * The ordering matters: the probe result is only accepted AFTER generation completes and
     * only if the screen has not moved since. Accepting it earlier would mean injecting an
     * observation the model never had a chance to reason about, which is how speculative systems
     * end up confidently acting on a screen that no longer exists.
     */
    suspend fun <T> speculate(generate: suspend () -> T): Result<T, P> = coroutineScope {
        attempts++
        val probeStart = System.currentTimeMillis()
        val probeJob = async { withTimeoutOrNull(probeTimeoutMs) { runCatching { probe() }.getOrNull() } }
        val generated = generate()
        val probeResult = probeJob.await()
        val probeMs = System.currentTimeMillis() - probeStart

        if (probeResult == null) {
            probeTimeouts++
            return@coroutineScope Result.NoProbe(generated, "probe did not return within ${probeTimeoutMs}ms")
        }

        totalProbeMs += probeMs
        probeSamples++

        val valid = runCatching { stillValid(probeResult) }.getOrDefault(false)
        if (!valid) {
            discarded++
            val rolled = runCatching { rollback(probeResult) }.getOrDefault(false)
            if (!rolled) rollbackFailures++
            return@coroutineScope Result.Discarded(
                generated,
                "the screen moved during generation (animation or permission dialog); restart this step from a fresh capture",
                rolled
            )
        }

        injected++
        Result.Injected(generated, probeResult, probeMs)
    }

    /** Fire-and-forget warmup for callers that own their own scope. */
    fun prewarm(scope: CoroutineScope) = scope.async { runCatching { probe() } }
}
