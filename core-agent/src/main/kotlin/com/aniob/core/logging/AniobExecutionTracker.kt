package com.aniob.core.logging

import com.aniob.core.domain.AniobAction

/**
 * Append-only structured execution trajectory tracker (similar to Browser-Use / OpenHands event streams).
 * Records each step's observation, thought, tool, latency, and outcome.
 * Thread-safe and queryable for UI tracker sheets (0 model calls, 0 captures).
 */
class AniobExecutionTracker {

    data class TrajectoryEvent(
        val stepIndex: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val observationSummary: String,
        val thought: String,
        val toolName: String,
        val action: AniobAction,
        val latencyMs: Long,
        val outcome: String, // "SUCCESS", "FAILURE", "BLOCKED", "CONFIRMATION_REQUIRED"
        val isVerified: Boolean = true,
        val tokensUsed: Int = 0,
        val textEvidence: String? = null,
        val targetBounds: com.aniob.core.domain.AniobRect? = null
    )

    private val events = mutableListOf<TrajectoryEvent>()

    @Synchronized
    fun recordEvent(event: TrajectoryEvent) {
        events.add(event)
    }

    @Synchronized
    fun getEvents(filter: String = "ALL"): List<TrajectoryEvent> {
        return when (filter.uppercase()) {
            "CORRECT" -> events.filter { it.isVerified && it.outcome == "SUCCESS" }
            "FAILED" -> events.filter { !it.isVerified || it.outcome == "FAILURE" }
            else -> ArrayList(events)
        }
    }

    @Synchronized
    fun getStepCount(): Int = events.size

    @Synchronized
    fun clear() {
        events.clear()
    }
}
