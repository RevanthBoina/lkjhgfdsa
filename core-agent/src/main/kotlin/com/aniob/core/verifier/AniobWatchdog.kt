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
     * Checks if a loop is detected: the same actionKey has been repeated N times
     * (regardless of screen — e.g. tapping the same element on a screen that keeps
     * re-rendering, which is indistinguishable from a stuck loop).
     * Triggers reflection: remedial BACK + failure reason loop_detected.
     */
    fun isLoopDetected(): Boolean {
        if (history.size < loopThreshold) return false
        val last = history.last()
        val recent = history.takeLast(loopThreshold)
        return recent.all { it.actionKey == last.actionKey }
    }

    fun reset() {
        history.clear()
    }
}
