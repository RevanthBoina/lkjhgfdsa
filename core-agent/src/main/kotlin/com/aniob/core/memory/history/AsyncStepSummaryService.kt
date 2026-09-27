package com.aniob.core.memory.history

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Plan-4.1: Shared asynchronous step-summary service.
 * - Non-blocking dispatch
 * - Bounded retry (3)
 * - Bounded flush (30s)
 * - Canonical step-id keying with alias resolution
 * - Concurrency 2 (serial queues push P90 past useful window)
 * - Zero blocking on main execution loop (falls back to detailed rendering)
 * - Frozen message rule: late summaries never mutate already frozen steps
 */
class AsyncStepSummaryService(
    private val summaryGenerator: (stepId: String, detail: String) -> String = { _, d -> "I observed the screen state: $d" }
) {
    private val executor = Executors.newFixedThreadPool(2)
    private val summaries = ConcurrentHashMap<String, String>()
    private val pendingTasks = ConcurrentHashMap<String, Future<*>>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val frozenStepIds = ConcurrentHashMap.newKeySet<String>()

    data class StepSummaryResult(
        val isReady: Boolean,
        val summaryText: String?,
        val fallbackDetail: String
    )

    fun registerAlias(alias: String, canonicalId: String) {
        aliases[alias] = canonicalId
    }

    private fun resolveCanonical(stepId: String): String {
        return aliases[stepId] ?: stepId
    }

    /**
     * Dispatches summary task asynchronously. Never blocks.
     */
    fun dispatchSummary(stepId: String, detail: String) {
        val canonical = resolveCanonical(stepId)
        if (frozenStepIds.contains(canonical) || summaries.containsKey(canonical)) {
            return
        }

        val future = executor.submit {
            var attempts = 0
            var success = false
            while (attempts < 3 && !success) {
                attempts++
                try {
                    val summary = summaryGenerator(canonical, detail)
                    NeutralWordingContract.assertNeutral(summary)
                    if (!frozenStepIds.contains(canonical)) {
                        summaries[canonical] = summary
                    }
                    success = true
                } catch (_: Exception) {
                    // Retry up to 3 times
                }
            }
        }
        pendingTasks[canonical] = future
    }

    /**
     * Non-blocking retrieval: returns summary if ready, or falls back to detailed rendering.
     * THE LOOP NEVER WAITS.
     */
    fun getSummaryOrFallback(stepId: String, detailedFallback: String): StepSummaryResult {
        val canonical = resolveCanonical(stepId)
        val ready = summaries[canonical]
        return if (ready != null) {
            StepSummaryResult(isReady = true, summaryText = ready, fallbackDetail = detailedFallback)
        } else {
            StepSummaryResult(isReady = false, summaryText = null, fallbackDetail = detailedFallback)
        }
    }

    /**
     * Freezes a step when it passes both context scrub edges.
     * Plan-4.8: Late summaries must never backfill a frozen step.
     */
    fun freezeStep(stepId: String) {
        val canonical = resolveCanonical(stepId)
        frozenStepIds.add(canonical)
        pendingTasks[canonical]?.cancel(true)
    }

    fun isFrozen(stepId: String): Boolean = frozenStepIds.contains(resolveCanonical(stepId))

    /**
     * Bounded flush for shutdown or explicit sync barriers (max 30s).
     */
    fun flush(timeoutSeconds: Long = 30) {
        executor.shutdown()
        try {
            executor.awaitTermination(timeoutSeconds.coerceAtMost(30), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
