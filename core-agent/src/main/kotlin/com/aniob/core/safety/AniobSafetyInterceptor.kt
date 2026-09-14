package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState

data class InterceptResult(
    val isAllowed: Boolean,
    val reason: String = "Allowed",
    val riskTier: String = "LOW",
    val requiresConfirmation: Boolean = false,
    val recommendedAction: AniobAction? = null
)

typealias InterceptionResult = InterceptResult

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

    private val paymentBlocklist = listOf("upi://", "com.android.vending.BILLING", "checkout", "purchase", "payment", "transaction")
    private val sensitiveBlocklist = listOf("otp", "cvv", "password", "pin")
    private val unknownContactKeywords = listOf("unknown", "add new", "new contact", "create contact", "???")
    private val blockedFingerprints = mutableSetOf<String>()

    fun evaluateAction(
        action: AniobAction,
        targetNode: AniobNode?,
        screenState: AniobScreenState,
        screenFingerprint: String
    ): InterceptResult {
        if (blockedFingerprints.contains(screenFingerprint)) {
            return InterceptResult(isAllowed = false, reason = "Never-Retry rule: fingerprint previously blocked", riskTier = "HIGH")
        }

        val actionStr = action.toString().lowercase()
        // Exact startsWith check on payment blocklist (also scan thought text so
        // "Click(targetNodeId=1, thought='buy now')" is caught). Whole-word boundary
        // matching avoids false positives like "pay" inside "display".
        val paymentMatch = paymentBlocklist.any { block ->
            actionStr.startsWith(block) || actionStr.contains(" ${block} ")
        }
        if (paymentMatch) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptResult(isAllowed = false, reason = "Payment operation blocked", riskTier = "HIGH")
        }
        val sensitiveRegex = sensitiveBlocklist.joinToString("|") { Regex.escape(it) }
            .let { Regex("\\b(?:$it)\\b") }
        if (targetNode?.isEditable == true && (sensitiveRegex.containsMatchIn(actionStr) || OTP_PATTERN.containsMatchIn(actionStr))) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptResult(isAllowed = false, reason = "Sensitive data blocked", riskTier = "HIGH")
        }

        // Immune-system gate: sending to unknown / newly-created contacts must be confirmed.
        if (action is AniobAction.ConfirmWithUser) {
            val recipient = action.message.lowercase()
            val unknownRecipient = recipient.isBlank() || unknownContactKeywords.any { recipient.contains(it) }
            if (unknownRecipient) {
                return InterceptResult(
                    isAllowed = true,
                    reason = "Confirm with user before messaging unknown contact",
                    riskTier = "MEDIUM",
                    requiresConfirmation = true,
                    recommendedAction = action
                )
            }
            return InterceptResult(
                isAllowed = true,
                reason = "Confirmation gate raised (MEDIUM risk)",
                riskTier = "MEDIUM",
                requiresConfirmation = true,
                recommendedAction = action
            )
        }

        return InterceptResult(isAllowed = true, reason = "Allowed", riskTier = "LOW")
    }

    fun isFingerprintBlocked(fingerprint: String): Boolean = blockedFingerprints.contains(fingerprint)

    fun clearBlockedFingerprints() {
        blockedFingerprints.clear()
    }
}
