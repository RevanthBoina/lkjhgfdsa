package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection

data class ReflectionResult(val isExpected: Boolean, val reason: String, val remedialAction: AniobAction? = null)

class AniobReflectionAgent {
    fun reflect(
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState?,
        lastAction: AniobAction,
        verifiedSuccess: Boolean
    ): ReflectionResult {
        if (screenBefore == null || screenAfter == null) return ReflectionResult(true, "No screen to compare")
        if (screenBefore.treeHash == screenAfter.treeHash && !verifiedSuccess) {
            return ReflectionResult(false, "No-effect: treeHash same after ${lastAction::class.simpleName}", AniobAction.Swipe(SwipeDirection.DOWN, 500))
        }
        if (screenAfter.nodes.isEmpty()) {
            return ReflectionResult(false, "Empty screen after action", AniobAction.SystemKey(com.aniob.core.domain.KeyType.BACK))
        }
        return ReflectionResult(true, "Screen changed ${screenBefore.treeHash.take(6)} -> ${screenAfter.treeHash.take(6)} verified=$verifiedSuccess")
    }
}
