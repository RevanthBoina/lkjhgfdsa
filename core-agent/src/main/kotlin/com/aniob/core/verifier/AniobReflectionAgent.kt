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

    /**
     * Judges a provisional `Finish`.
     *
     * Ordering matters for token cost: a deterministic pass is accepted immediately, a
     * fully-checkable deterministic failure is rejected *without* spending an LLM call, and only
     * a partially checkable case reaches [llmJudge] — exactly one call.
     */
    fun reflectFinish(
        criteria: com.aniob.core.domain.SuccessCriteria,
        finalScreen: AniobScreenState,
        steps: Int,
        deterministic: DeterministicVerifier.VerificationResult,
        llmJudge: (String) -> com.aniob.core.execution.ReflectionOutcome = {
            com.aniob.core.execution.ReflectionOutcome.rejected(it, it)
        }
    ): com.aniob.core.execution.ReflectionOutcome {
        if (deterministic.isExpected) {
            return com.aniob.core.execution.ReflectionOutcome.confirmed(deterministic.reason)
        }
        // Fully checkable -> deterministic verdict is authoritative, no LLM needed.
        if (criteria.isFullyCheckable) {
            return com.aniob.core.execution.ReflectionOutcome.rejected(
                deterministic.reason,
                "The screen does not satisfy the task criteria yet (${deterministic.reason})."
            )
        }
        return llmJudge(deterministic.reason)
    }
}

