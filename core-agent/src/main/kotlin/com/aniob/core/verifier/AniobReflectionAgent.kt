package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.exec.AniobErrorClass
import com.aniob.core.exec.AniobErrorTaxonomy

data class ReflectionResult(val isExpected: Boolean, val reason: String, val remedialAction: AniobAction? = null)

class AniobReflectionAgent {
    fun reflect(
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState?,
        lastAction: AniobAction,
        verifiedSuccess: Boolean,
        errorClass: AniobErrorClass? = null
    ): ReflectionResult {
        if (screenBefore == null || screenAfter == null) return ReflectionResult(true, "No screen to compare")

        val detectedError = errorClass ?: when {
            screenBefore.treeHash == screenAfter.treeHash && !verifiedSuccess -> AniobErrorClass.NO_EFFECT
            screenAfter.nodes.isEmpty() -> AniobErrorClass.WRONG_SCREEN
            screenAfter.packageName != screenBefore.packageName && lastAction !is AniobAction.OpenApp -> AniobErrorClass.WRONG_SCREEN
            else -> null
        }

        if (detectedError != null) {
            val canRetry = AniobErrorTaxonomy.recordError(detectedError)
            if (!canRetry) {
                return ReflectionResult(
                    false,
                    "Circuit breaker: ${detectedError.name} failed ${AniobErrorTaxonomy.MAX_RETRIES_PER_CLASS} times",
                    AniobAction.Fail("Circuit breaker ${detectedError.name}")
                )
            }
            val handler = AniobErrorTaxonomy.getHandler(detectedError)
            val result = handler(detectedError)
            return ReflectionResult(false, "Deterministic handler for ${detectedError.name}: ${result.reason}", result.action)
        }
        return ReflectionResult(true, "Screen changed ${screenBefore.treeHash.take(6)} -> ${screenAfter.treeHash.take(6)} verified=$verifiedSuccess")
    }
}

