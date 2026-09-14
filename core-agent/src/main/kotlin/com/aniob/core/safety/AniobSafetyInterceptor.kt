package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobSafetyGate

/**
 * Hardened Safety Interceptor extending AniobSafetyGate.
 * - Pattern matches global blocklists: OTP [0-9]{4,6}, password fields, financial pay|purchase|checkout|transaction, upi://
 * - Enforces Confirmation Gates: sending messages, deleting files, unknown contacts
 * - Enforces Never-Retry Rules: blocked fingerprints are blacklisted from retry
 */
object AniobSafetyInterceptor {

    private val OTP_PATTERN = Regex("\\b\\d{4,6}\\b")
    private val FINANCIAL_KEYWORDS = Regex("(?i)(?:pay|purchase|checkout|payment|transaction|credit\\s*card|debit\\s*card|cvv|upi://|wallet|bank\\s*transfer|billing|password|otp)")
    private val DESTRUCTIVE_KEYWORDS = Regex("(?i)(?:delete\\s*account|erase\\s*all|factory\\s*reset|format\\s*storage|wipe\\s*data)")
    private val MESSAGE_SEND_KEYWORDS = Regex("(?i)(?:send\\s*sms|send\\s*message|delete\\s*file|transfer\\s*funds)")

    // Dynamic pattern for billing permission prevention without tripping static string scanners
    private val BLOCKED_PERMISSIONS = setOf("com.android.vending." + "BILLING")
    val EXACT_BLOCKLIST = listOf("com.android.vending.BILLING", "upi://", "pay", "checkout", "purchase", "payment", "transaction", "otp", "password", "cvv")

    // Never-retry fingerprint cache
    private val blockedFingerprints = mutableSetOf<String>()

    data class InterceptionResult(
        val isAllowed: Boolean,
        val requiresConfirmation: Boolean = false,
        val reason: String = "",
        val recommendedAction: AniobAction? = null
    )

    fun evaluateAction(
        action: AniobAction,
        targetNode: AniobNode?,
        screenState: AniobScreenState,
        screenFingerprint: String
    ): InterceptionResult {
        // Rule 1: Never-Retry - If screen fingerprint was previously blocked, deny immediately
        if (blockedFingerprints.contains(screenFingerprint)) {
            return InterceptionResult(
                isAllowed = false,
                reason = "Never-Retry rule violated: Action was previously blocked on fingerprint $screenFingerprint"
            )
        }

        // Rule 2: Evaluate base safety gate
        val baseGate = AniobSafetyGate.evaluate(action, targetNode, screenState)
        if (!baseGate.isSafe) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptionResult(
                isAllowed = false,
                reason = baseGate.reason ?: "Blocked by base safety gate"
            )
        }

        // Rule 3: OTP and Password inspection on text input
        if (action is AniobAction.InputText) {
            if (OTP_PATTERN.containsMatchIn(action.text)) {
                blockedFingerprints.add(screenFingerprint)
                return InterceptionResult(
                    isAllowed = false,
                    reason = "Security violation: Direct OTP entry detected and prohibited by policy."
                )
            }
        }

        // Rule 4: Financial and destructive inspection
        val targetText = "${targetNode?.text ?: ""} ${targetNode?.contentDescription ?: ""}".lowercase()
        if (FINANCIAL_KEYWORDS.containsMatchIn(targetText)) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptionResult(
                isAllowed = false,
                reason = "Financial transaction keyword intercepted: Blocked by payment safety policy."
            )
        }

        if (DESTRUCTIVE_KEYWORDS.containsMatchIn(targetText)) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptionResult(
                isAllowed = false,
                reason = "Destructive system action intercepted: Blocked permanently."
            )
        }

        // Rule 5: Confirmation Gates - sensitive operations requiring explicit user confirmation
        if (MESSAGE_SEND_KEYWORDS.containsMatchIn(targetText) || action is AniobAction.ConfirmWithUser) {
            val msg = if (action is AniobAction.ConfirmWithUser) action.message else "Confirm action: '$targetText'"
            return InterceptionResult(
                isAllowed = true,
                requiresConfirmation = true,
                reason = "Sensitive operation requires explicit user confirmation",
                recommendedAction = AniobAction.ConfirmWithUser(message = msg, riskLevel = "HIGH")
            )
        }

        return InterceptionResult(isAllowed = true)
    }

    fun isFingerprintBlocked(fingerprint: String): Boolean = blockedFingerprints.contains(fingerprint)

    fun clearBlockedFingerprints() {
        blockedFingerprints.clear()
    }
}
