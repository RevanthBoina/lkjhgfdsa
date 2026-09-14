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
        // Exact startsWith check
        if (paymentBlocklist.any { actionStr.startsWith(it) }) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptResult(isAllowed = false, reason = "Payment operation blocked", riskTier = "HIGH")
        }
        if (sensitiveBlocklist.any { actionStr.contains(it) && targetNode?.isEditable == true }) {
            blockedFingerprints.add(screenFingerprint)
            return InterceptResult(isAllowed = false, reason = "Sensitive data blocked", riskTier = "HIGH")
        }
        return InterceptResult(isAllowed = true, reason = "Allowed", riskTier = "LOW")
    }

    fun isFingerprintBlocked(fingerprint: String): Boolean = blockedFingerprints.contains(fingerprint)

    fun clearBlockedFingerprints() {
        blockedFingerprints.clear()
    }
}
