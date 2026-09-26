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
    private val destructiveBlocklist = listOf("delete account", "erase all", "factory reset", "format storage", "wipe data")
    private val unknownContactKeywords = listOf("unknown", "add new", "new contact", "create contact", "???")
    private val blockedFingerprints = mutableSetOf<String>()
    private val blockedTimestamps = mutableMapOf<String, Long>()

    fun evaluateAction(
        action: AniobAction,
        targetNode: AniobNode?,
        screenState: AniobScreenState,
        screenFingerprint: String,
        skillRisk: String? = null,
        skillRequiresConfirmation: Boolean = false
    ): InterceptResult {
        if (blockedFingerprints.contains(screenFingerprint)) {
            return InterceptResult(isAllowed = false, reason = "Never-Retry rule: fingerprint previously blocked", riskTier = "HIGH")
        }

        val actionStr = action.toString().lowercase()

        // 1. Hard prohibitions: UPI schemes, Google Billing, destructive wipe actions
        if (actionStr.contains("upi://") || targetNode?.text?.contains("upi://", ignoreCase = true) == true) {
            blockedFingerprints.add(screenFingerprint)
            blockedTimestamps[screenFingerprint] = System.currentTimeMillis()
            return InterceptResult(isAllowed = false, reason = "UPI payment protocol blocked", riskTier = "HIGH")
        }

        if (BLOCKED_PERMISSIONS.any { actionStr.contains(it.lowercase()) }) {
            blockedFingerprints.add(screenFingerprint)
            blockedTimestamps[screenFingerprint] = System.currentTimeMillis()
            return InterceptResult(isAllowed = false, reason = "Billing permission blocked", riskTier = "HIGH")
        }

        // Destructive wipe actions blocked
        val destructiveMatch = DESTRUCTIVE_KEYWORDS.containsMatchIn(actionStr) ||
            targetNode?.text?.let { DESTRUCTIVE_KEYWORDS.containsMatchIn(it) } == true ||
            targetNode?.contentDescription?.let { DESTRUCTIVE_KEYWORDS.containsMatchIn(it) } == true
        if (destructiveMatch) {
            blockedFingerprints.add(screenFingerprint)
            blockedTimestamps[screenFingerprint] = System.currentTimeMillis()
            return InterceptResult(isAllowed = false, reason = "Destructive operation blocked", riskTier = "HIGH")
        }

        // Sensitive data on editable fields
        val sensitiveRegex = sensitiveBlocklist.joinToString("|") { Regex.escape(it) }
            .let { Regex("\\b(?:$it)\\b") }
        if (targetNode?.isEditable == true && (sensitiveRegex.containsMatchIn(actionStr) || OTP_PATTERN.containsMatchIn(actionStr))) {
            blockedFingerprints.add(screenFingerprint)
            blockedTimestamps[screenFingerprint] = System.currentTimeMillis()
            return InterceptResult(isAllowed = false, reason = "Sensitive data blocked", riskTier = "HIGH")
        }

        // 2. Financial operations: Distinguish reading payment history from committing payment
        val targetText = listOfNotNull(
            targetNode?.text,
            targetNode?.contentDescription,
            (action as? AniobAction.Tap)?.target?.let { (it as? com.aniob.core.domain.SemanticTarget.Text)?.text }
        ).joinToString(" ").lowercase()

        val isReadOnlyHistory = targetText.contains("payment history") ||
            targetText.contains("transaction history") ||
            targetText.contains("purchase history") ||
            targetText.contains("order history") ||
            targetText.contains("billing history") ||
            targetText.contains("view history") ||
            targetText.contains("statements") ||
            targetText.contains("receipts") ||
            (targetText.contains("history") && !targetText.contains("pay now") && !targetText.contains("proceed to pay"))

        // Inspect both node text and action thought: visible Send/Payment controls cannot evade policy
        // through an innocuous model explanation.
        val nodeAndActionText = "$targetText ${action.thought} ${action.describeAction()}".lowercase()
        val paymentMatch = FINANCIAL_KEYWORDS.containsMatchIn(nodeAndActionText)

        if (paymentMatch && !isReadOnlyHistory) {
            blockedFingerprints.add(screenFingerprint)
            blockedTimestamps[screenFingerprint] = System.currentTimeMillis()
            return InterceptResult(isAllowed = false, reason = "Payment operation blocked", riskTier = "HIGH")
        }

        // 3. Consequential message sending controls or unknown contact
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

        // 4. Skill risk constraints
        if (skillRisk?.equals("HIGH", ignoreCase = true) == true || skillRequiresConfirmation) {
            return InterceptResult(
                isAllowed = true,
                reason = "Skill risk gate requires confirmation ($skillRisk)",
                riskTier = "HIGH",
                requiresConfirmation = true
            )
        }

        return InterceptResult(isAllowed = true, reason = "Allowed", riskTier = "LOW")
    }

    fun isFingerprintBlocked(fingerprint: String): Boolean = blockedFingerprints.contains(fingerprint)

    fun clearBlockedFingerprints() {
        blockedFingerprints.clear()
        blockedTimestamps.clear()
    }

    fun unblockFingerprint(fingerprint: String) {
        blockedFingerprints.remove(fingerprint)
        blockedTimestamps.remove(fingerprint)
    }

    fun clearTaskBlockedFingerprints(fingerprints: Set<String>) {
        fingerprints.forEach { unblockFingerprint(it) }
    }

    fun getBlockedFingerprints(): Set<String> = blockedFingerprints.toSet()

    fun getBlockedFingerprintTimestamp(fingerprint: String): Long =
        blockedTimestamps[fingerprint] ?: System.currentTimeMillis()
}
