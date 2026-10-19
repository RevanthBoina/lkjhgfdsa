package com.aniob.core.policy

import com.aniob.core.domain.AniobAction

/**
 * Adaptive Observe/Act Burst Policy.
 * Replaces repetitive capture-observe-after-every-touch with confident multi-action bursts:
 * - If last 2 actions were in the same package AND DeterministicVerifier confirmed AND no scroll occurred:
 *   burst 2-3 actions without full intermediate hierarchy capture/diffing.
 * - Otherwise: triggers full screen observation.
 * Reduces perceive_ms from ~80ms p50 to ~40ms during predictable workflows.
 */
class AniobObservationPolicy(
    maxBurstSteps: Int = 3
) {

    /**
     * Live burst ceiling. Research ablation switches this at task start: 3 for the burst modes,
     * 1 for BASELINE / INTENT_SHORTCUT_ONLY so every step performs a full observation.
     * Defaults to the constructor value, so non-experimental behaviour is unchanged.
     */
    @Volatile
    private var burstLimit: Int = maxBurstSteps.coerceAtLeast(1)

    /** Changes the burst ceiling without recreating the policy (keeps action history). */
    fun setBurstLimit(maxBurstSteps: Int) {
        burstLimit = maxBurstSteps.coerceAtLeast(1)
    }

    data class ActionHistoryItem(
        val action: AniobAction,
        val packageName: String,
        val verifiedSuccess: Boolean,
        val wasScroll: Boolean
    )

    private val recentActions = mutableListOf<ActionHistoryItem>()
    private var currentBurstCount = 0

    @Synchronized
    fun recordActionOutcome(
        action: AniobAction,
        packageName: String,
        verifiedSuccess: Boolean
    ) {
        val isScroll = action is AniobAction.Swipe
        recentActions.add(
            ActionHistoryItem(
                action = action,
                packageName = packageName,
                verifiedSuccess = verifiedSuccess,
                wasScroll = isScroll
            )
        )
        if (recentActions.size > 10) {
            recentActions.removeAt(0)
        }

        if (verifiedSuccess && !isScroll) {
            currentBurstCount++
        } else {
            // Error, verification failure, or scroll reset burst sequence
            currentBurstCount = 0
        }
    }

    /**
     * Determines whether the agent should perform a full screen observation capture
     * or proceed immediately with an action burst.
     */
    @Synchronized
    fun shouldObserve(): Boolean {
        // Always observe if no previous actions or burst limit reached
        if (recentActions.size < 2 || currentBurstCount >= burstLimit) {
            currentBurstCount = 0
            return true
        }

        val lastTwo = recentActions.takeLast(2)
        val sameApp = lastTwo[0].packageName == lastTwo[1].packageName
        val bothConfirmed = lastTwo.all { it.verifiedSuccess }
        val noScroll = lastTwo.none { it.wasScroll }

        // Repeated identical action means the previous gesture likely landed mid-animation and
        // produced no visible change. Force a fresh observation instead of chaining a burst on
        // a screen that may still be animating.
        if (lastTwo[0].action.describeAction() == lastTwo[1].action.describeAction()) {
            currentBurstCount = 0
            return true
        }

        val canBurst = sameApp && bothConfirmed && noScroll
        if (canBurst) {
            // Confident burst: Skip intermediate capture (reduces perception latency to ~40ms)
            return false
        }

        currentBurstCount = 0
        return true
    }

    @Synchronized
    fun reset() {
        recentActions.clear()
        currentBurstCount = 0
    }
}
