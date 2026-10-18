package com.aniob.core.domain

/**
 * One model reply that plans several actions on the *current* screen.
 *
 * Only the burst conditions ask for this shape: when every target is already visible, a tap, a
 * typed value and a submit press can be planned in one completion instead of three round trips.
 * The list is capped at [MAX_ACTIONS] so a single reply can never turn into a long blind run.
 */
data class ActionBurst(
    val thought: String,
    /** The model believes the goal is complete once these actions land. */
    val isComplete: Boolean,
    val actions: List<AniobAction>
) {
    /**
     * Actions that may be dispatched without a fresh model call. When the model reported
     * completion, the whole list is executed and a provisional finish carries the step to
     * verification; otherwise the last action stays with the normal step pipeline.
     */
    val head: List<AniobAction>
        get() = when {
            actions.isEmpty() -> emptyList()
            isComplete -> actions
            else -> actions.dropLast(1)
        }

    /** The action a normal (non-burst) step would have returned on its own. */
    val tail: AniobAction?
        get() = if (isComplete) null else actions.lastOrNull()

    companion object {
        /** Hard ceiling for one reply; larger arrays are truncated, never executed in full. */
        const val MAX_ACTIONS = 3
    }
}
