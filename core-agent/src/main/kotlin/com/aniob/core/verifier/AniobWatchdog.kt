package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction

/**
 * Watchdog Anti-Loop Guard (Deft pattern).
 * Detects repetitive state-action loops and forces replanning.
 */
class AniobWatchdog(
    private val loopThreshold: Int = 3
) {
    data class ActionHistoryEntry(
        val screenHash: String,
        val actionKey: String
    )

    private val history = mutableListOf<ActionHistoryEntry>()

    fun record(screenHash: String, action: AniobAction) {
        // Semantic keys only — coordinates are not part of an action's identity any more.
        val actionKey = action.describeAction()
        history.add(ActionHistoryEntry(screenHash, actionKey))
        if (history.size > 20) {
            history.removeAt(0)
        }
    }

    /**
     * Checks if a loop is detected: either the same actionKey has been repeated N times
     * (takeLast(loopThreshold)), or an oscillating cycle (e.g. A->B->A->B) is detected.
     */
    fun isLoopDetected(): Boolean {
        if (history.size < loopThreshold) return false
        val last = history.last()
        val recent = history.takeLast(loopThreshold)
        if (recent.all { it.actionKey == last.actionKey }) return true
        return isOscillating()
    }

    /**
     * Detects sub-sequence oscillation (e.g. A->B->A->B or A->B->C->A->B->C).
     */
    fun isOscillating(): Boolean {
        if (history.size < 4) return false
        val keys = history.map { it.actionKey }
        for (cycleLen in 2..3) {
            val windowSize = cycleLen * 2
            if (keys.size >= windowSize) {
                val window = keys.takeLast(windowSize)
                val firstHalf = window.take(cycleLen)
                val secondHalf = window.takeLast(cycleLen)
                if (firstHalf == secondHalf) {
                    return true
                }
            }
        }
        return false
    }

    fun reset() {
        history.clear()
    }
}
