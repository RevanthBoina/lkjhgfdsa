package com.aniob.core.exec

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.domain.KeyType

enum class AniobErrorClass {
    ELEMENT_NOT_FOUND, // NoSuchElementError
    STALE_ELEMENT, // StaleElementReferenceError - node existed but gone after scroll
    NO_EFFECT, // tap did nothing, treeHash same
    WRONG_SCREEN, // package changed unexpectedly or expected package not foreground
    TIMEOUT, // bounded execution timeout 15s
    BUDGET_EXCEEDED // max steps 10
}

data class ErrorHandlerResult(
    val action: AniobAction?,
    val shouldRetry: Boolean,
    val shouldAbort: Boolean,
    val reason: String
)

object AniobErrorTaxonomy {
    private val errorCounters = mutableMapOf<AniobErrorClass, Int>()
    const val MAX_RETRIES_PER_CLASS = 2
    
    fun getHandler(errorClass: AniobErrorClass): (AniobErrorClass) -> ErrorHandlerResult {
        return when (errorClass) {
            AniobErrorClass.ELEMENT_NOT_FOUND -> { _ ->
                ErrorHandlerResult(
                    action = AniobAction.Swipe(SwipeDirection.DOWN, 500),
                    shouldRetry = true,
                    shouldAbort = false,
                    reason = "Scroll + re-find bounded retry"
                )
            }
            AniobErrorClass.STALE_ELEMENT -> { _ ->
                ErrorHandlerResult(
                    action = null, // Re-capture, no action
                    shouldRetry = true,
                    shouldAbort = false,
                    reason = "Re-capture fresh tree, stale reference"
                )
            }
            AniobErrorClass.NO_EFFECT -> { _ ->
                ErrorHandlerResult(
                    action = null, // Wait-for-idle then re-attempt once
                    shouldRetry = true,
                    shouldAbort = false,
                    reason = "Wait-for-idle then re-attempt once"
                )
            }
            AniobErrorClass.WRONG_SCREEN -> { _ ->
                ErrorHandlerResult(
                    action = AniobAction.SystemKey(KeyType.BACK),
                    shouldRetry = true,
                    shouldAbort = false,
                    reason = "BACK + verify package foreground"
                )
            }
            AniobErrorClass.TIMEOUT -> { _ ->
                ErrorHandlerResult(
                    action = null,
                    shouldRetry = false,
                    shouldAbort = true,
                    reason = "Timeout 15s - abort, no retry"
                )
            }
            AniobErrorClass.BUDGET_EXCEEDED -> { _ ->
                ErrorHandlerResult(
                    action = AniobAction.Finish("Budget exceeded"),
                    shouldRetry = false,
                    shouldAbort = true,
                    reason = "Max steps 10 - abort"
                )
            }
        }
    }
    
    fun recordError(errorClass: AniobErrorClass): Boolean {
        val count = errorCounters.getOrDefault(errorClass, 0) + 1
        errorCounters[errorClass] = count
        return count <= MAX_RETRIES_PER_CLASS // Circuit breaker per error class
    }
    
    fun reset() { errorCounters.clear() }
    fun getCount(errorClass: AniobErrorClass): Int = errorCounters.getOrDefault(errorClass, 0)
}
