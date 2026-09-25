package com.aniob.app.workflow

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

/**
 * TaskOwner: Single active lease, generation counter, and cancellable execution owner
 * across both existing screen automation and new typed workflow tools.
 *
 * Invariant: Exactly one active execution lease exists at a time.
 * A waiting website or document state is durable but holds NO coroutine or execution lease.
 */
class TaskOwner {

    private val generationCounter = AtomicLong(0L)
    @Volatile private var activeJob: Job? = null
    @Volatile private var currentTaskId: String? = null
    @Volatile private var currentActionId: String? = null

    /**
     * Acquires a new execution lease for the specified task.
     * Cancels any in-flight active job, bumps generation, and sets active ownership.
     */
    @Synchronized
    fun acquireLease(taskId: String, actionId: String = ""): Long {
        val prevJob = activeJob
        if (prevJob != null && prevJob.isActive) {
            prevJob.cancel()
        }
        activeJob = null
        currentTaskId = taskId
        currentActionId = actionId
        return generationCounter.incrementAndGet()
    }

    /**
     * Binds a running coroutine Job to the current lease generation.
     * If the generation is already stale, the job is immediately cancelled.
     */
    @Synchronized
    fun attachJob(generation: Long, job: Job): Boolean {
        if (generationCounter.get() != generation) {
            job.cancel()
            return false
        }
        activeJob = job
        return true
    }

    /**
     * Stops work scoped to [taskId] (or any active task if taskId is null).
     * Returns true if an active job was cancelled.
     */
    @Synchronized
    fun stop(taskId: String? = null): Boolean {
        if (taskId != null && currentTaskId != taskId) {
            return false
        }
        val job = activeJob
        activeJob = null
        val wasActive = job?.isActive == true
        job?.cancel()
        currentTaskId = null
        currentActionId = null
        return wasActive
    }

    /**
     * Releases the lease if the given generation is current.
     */
    @Synchronized
    fun releaseLease(generation: Long) {
        if (generationCounter.get() == generation) {
            activeJob = null
            currentTaskId = null
            currentActionId = null
        }
    }

    /**
     * True if a coroutine job is actively executing under the current lease.
     */
    fun isActive(): Boolean {
        return activeJob?.isActive == true
    }

    fun currentGeneration(): Long = generationCounter.get()

    fun activeTaskId(): String? = currentTaskId

    fun activeActionId(): String? = currentActionId
}
