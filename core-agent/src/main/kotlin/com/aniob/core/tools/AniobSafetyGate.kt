package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState

/**
 * Intercepts potentially sensitive or destructive actions to protect user security.
 */
object AniobSafetyGate {

    private val SENSITIVE_KEYWORDS = listOf(
        "pay", "checkout", "transfer", "credit card", "debit card", "cvv",
        "delete account", "erase all", "factory reset", "format",
        "send money", "confirm payment", "buy now"
    )

    private val SENSITIVE_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.android.vending"
    )

    data class SafetyCheckResult(
        val isSafe: Boolean,
        val reason: String? = null
    )

    fun evaluate(
        action: AniobAction,
        targetNode: AniobNode?,
        screenState: AniobScreenState
    ): SafetyCheckResult {
        // Block sensitive actions in payment apps or on payment elements
        if (screenState.packageName in SENSITIVE_PACKAGES) {
            return SafetyCheckResult(
                isSafe = false,
                reason = "Target application (${screenState.packageName}) is restricted by safety policy."
            )
        }

        targetNode?.let { node ->
            val combinedText = "${node.text} ${node.contentDescription}".lowercase()
            for (keyword in SENSITIVE_KEYWORDS) {
                if (combinedText.contains(keyword)) {
                    return SafetyCheckResult(
                        isSafe = false,
                        reason = "Element contains sensitive keyword '$keyword'. Explicit user confirmation required."
                    )
                }
            }
        }

        return SafetyCheckResult(isSafe = true)
    }
}
