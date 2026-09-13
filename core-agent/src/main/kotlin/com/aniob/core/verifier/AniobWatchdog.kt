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
        val actionKey = when (action) {
            is AniobAction.Click -> "CLICK_${action.targetNodeId}"
            is AniobAction.InputText -> "INPUT_${action.targetNodeId}_${action.text}"
            is AniobAction.Swipe -> "SWIPE_${action.direction}"
            is AniobAction.PressKey -> "KEY_${action.key}"
            is AniobAction.Wait -> "WAIT"
            is AniobAction.Finish -> "FINISH"
            is AniobAction.Fail -> "FAIL"
        }
        history.add(ActionHistoryEntry(screenHash, actionKey))
        if (history.size > 20) {
            history.removeAt(0)
        }
    }

    /**
     * Checks if the last action+screen combination has repeated N times.
     */
    fun isLoopDetected(): Boolean {
        if (history.size < loopThreshold) return false
        val last = history.last()
        val recentDuplicates = history.takeLast(loopThreshold)
        return recentDuplicates.all { it.screenHash == last.screenHash && it.actionKey == last.actionKey }
    }

    fun reset() {
        history.clear()
    }
}
