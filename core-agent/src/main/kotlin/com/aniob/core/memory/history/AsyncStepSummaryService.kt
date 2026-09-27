package com.aniob.core.memory.history

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Plan-4.1: Shared asynchronous step-summary service.
 *
 * - Non-blocking dispatch; THE LOOP NEVER WAITS.
 * - Bounded retry (3) that feeds the contract violation back so regeneration can converge.
 * - `awaitQuiescence` is a bounded BARRIER (does not kill the pool); `shutdown` is separate.
 * - Canonical step-id keying with alias resolution that also remaps already-stored entries.
 * - Concurrency 2: a serial queue pushes summary-ready P90 past the window where it is useful.
 * - Daemon workers, so a forgotten service can never hold the process open.
 * - Plan-4.8 frozen rule enforced atomically, not check-then-write.
 * - Every drop is counted and reported; a silently discarded summary is a silently corrupted
 *   history, which is the exact failure Plan 4 exists to prevent.
 */
class AsyncStepSummaryService(
    private val generator: SummaryGenerator = SummaryGenerator { _, d, _ -> "I observed the screen state: $d" },
    private val maxAttempts: Int = 3,
    /** Notified when a step permanently fails to produce a contract-clean summary. */
    private val onPermanentFailure: (stepId: String, violations: List<String>) -> Unit = { _, _ -> }
) {

    /** Receives the violations from the previous attempt so the rewrite can actually differ. */
    fun interface SummaryGenerator {
        fun generate(stepId: String, detail: String, priorViolations: List<String>): String
    }

    private val threadIds = AtomicInteger(0)
    private val executor = Executors.newFixedThreadPool(CONCURRENCY, ThreadFactory { r ->
        Thread(r, "aniob-step-summary-${threadIds.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    })

    private val summaries = ConcurrentHashMap<String, String>()
    private val pendingTasks = ConcurrentHashMap<String, Future<*>>()
    private val dispatchedAt = ConcurrentHashMap<String, Long>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val frozenStepIds = ConcurrentHashMap.newKeySet<String>()

    private val retries = AtomicInteger(0)
    private val drops = AtomicInteger(0)
    private val completed = AtomicInteger(0)
    private val totalLagMs = AtomicLong(0)
    private val maxLagMs = AtomicLong(0)

    data class StepSummaryResult(
        val isReady: Boolean,
        val summaryText: String?,
        val fallbackDetail: String
    ) {
        /** What actually goes in the prompt. Ready summary, else the detailed rendering. */
        fun render(): String = summaryText ?: fallbackDetail
    }

    /** Plan-8.6 telemetry: summary-ready lag is a tracked number, not a mystery. */
    data class Stats(
        val completed: Int,
        val pending: Int,
        val retries: Int,
        val permanentDrops: Int,
        val meanLagMs: Long,
        val maxLagMs: Long
    )

    fun stats(): Stats = Stats(
        completed = completed.get(),
        pending = pendingTasks.count { !it.value.isDone },
        retries = retries.get(),
        permanentDrops = drops.get(),
        meanLagMs = if (completed.get() == 0) 0 else totalLagMs.get() / completed.get(),
        maxLagMs = maxLagMs.get()
    )

    /**
     * Points [alias] at [canonicalId] and migrates anything already stored under the alias.
     * Without the migration a step dispatched under a provisional id is lost the moment the
     * canonical id is assigned.
     */
    fun registerAlias(alias: String, canonicalId: String) {
        if (alias == canonicalId) return
        aliases[alias] = canonicalId
        summaries.remove(alias)?.let { summaries.putIfAbsent(canonicalId, it) }
        dispatchedAt.remove(alias)?.let { dispatchedAt.putIfAbsent(canonicalId, it) }
        pendingTasks.remove(alias)?.let { pendingTasks.putIfAbsent(canonicalId, it) }
        if (frozenStepIds.remove(alias)) frozenStepIds.add(canonicalId)
    }

    /** Follows alias chains, with a bound so a cycle cannot hang the loop. */
    private fun resolveCanonical(stepId: String): String {
        var id = stepId
        var hops = 0
        while (hops++ < MAX_ALIAS_HOPS) {
            id = aliases[id] ?: return id
        }
        return id
    }

    /** Dispatches a summary task asynchronously. Never blocks, never throws. */
    fun dispatchSummary(stepId: String, detail: String) {
        val canonical = resolveCanonical(stepId)
        if (frozenStepIds.contains(canonical) || summaries.containsKey(canonical)) return
        pendingTasks[canonical]?.let { if (!it.isDone) return }

        dispatchedAt.putIfAbsent(canonical, System.currentTimeMillis())

        val future = try {
            executor.submit { runSummary(canonical, detail) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Pool already shut down: count it rather than blowing up the caller.
            drops.incrementAndGet()
            onPermanentFailure(canonical, listOf("summary service is shut down"))
            return
        }
        pendingTasks[canonical] = future
    }

    private fun runSummary(canonical: String, detail: String) {
        var violations = emptyList<String>()
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            if (attempt > 1) retries.incrementAndGet()
            if (frozenStepIds.contains(canonical)) return
            val candidate = try {
                generator.generate(canonical, detail, violations)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                violations = listOf("generator threw: ${e.message}")
                continue
            }
            val report = NeutralWordingContract.validate(candidate)
            if (!report.isValid) {
                violations = report.violations
                continue
            }
            commit(canonical, candidate)
            return
        }
        drops.incrementAndGet()
        onPermanentFailure(canonical, violations)
    }

    /**
     * Plan-4.8: atomic. `putIfAbsent` then re-check the frozen set and roll back, so a worker
     * that was mid-flight when the step froze cannot leave a value behind.
     */
    private fun commit(canonical: String, summary: String) {
        if (frozenStepIds.contains(canonical)) return
        summaries.putIfAbsent(canonical, summary)
        if (frozenStepIds.contains(canonical)) {
            summaries.remove(canonical, summary)
            return
        }
        val lag = System.currentTimeMillis() - (dispatchedAt[canonical] ?: System.currentTimeMillis())
        completed.incrementAndGet()
        totalLagMs.addAndGet(lag)
        maxLagMs.getAndUpdate { prev -> if (lag > prev) lag else prev }
    }

    /** Non-blocking retrieval: ready summary, or the detailed fallback. THE LOOP NEVER WAITS. */
    fun getSummaryOrFallback(stepId: String, detailedFallback: String): StepSummaryResult {
        val canonical = resolveCanonical(stepId)
        val ready = summaries[canonical]
        return StepSummaryResult(
            isReady = ready != null,
            summaryText = ready,
            fallbackDetail = detailedFallback
        )
    }

    /**
     * Freezes a step when it passes both context scrub edges.
     * Plan-4.8: a late summary must never backfill a frozen step.
     */
    fun freezeStep(stepId: String) {
        val canonical = resolveCanonical(stepId)
        frozenStepIds.add(canonical)
        pendingTasks.remove(canonical)?.cancel(true)
    }

    fun isFrozen(stepId: String): Boolean = frozenStepIds.contains(resolveCanonical(stepId))

    /**
     * Bounded BARRIER (Plan-4.1 "bounded flush 30s"). Waits for in-flight work, caps at 30s,
     * and leaves the service usable. Returns true if everything drained.
     */
    fun awaitQuiescence(timeoutSeconds: Long = 30): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtMost(30) * 1000
        for (f in pendingTasks.values.toList()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            try {
                f.get(remaining, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Individual failures are already counted in runSummary.
            }
        }
        pendingTasks.entries.removeIf { it.value.isDone }
        return true
    }

    /** Terminal. Call from onCleared / task-scope teardown, not between steps. */
    fun shutdown(timeoutSeconds: Long = 5) {
        executor.shutdownNow()
        try {
            executor.awaitTermination(timeoutSeconds.coerceAtMost(30), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** "1 -> 2: a serial queue pushed summary-ready P90 past the scrub window." */
        const val CONCURRENCY = 2
        private const val MAX_ALIAS_HOPS = 8

        /**
         * Convenience for callers that ignore regeneration feedback (tests, fixed-format
         * summarizers). Kept as a factory rather than a secondary constructor: a two-arg lambda
         * and the three-arg SAM are ambiguous at the call site.
         */
        fun simple(f: (stepId: String, detail: String) -> String): AsyncStepSummaryService =
            AsyncStepSummaryService(SummaryGenerator { id, detail, _ -> f(id, detail) })
    }
}
