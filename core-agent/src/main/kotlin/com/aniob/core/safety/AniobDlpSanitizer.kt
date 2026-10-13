package com.aniob.core.safety

/**
 * In-process Zero-Trust Data Loss Prevention (DLP) Sanitizer.
 *
 * Strips or masks Personally Identifiable Information (PII), financial credentials,
 * authentication tokens, passwords, and OTPs before prompt construction or logging.
 *
 * Pure Kotlin/JVM: 0 android.* imports.
 * Regexes are strictly escaped to prevent ICU / OpenJDK syntax differences.
 */
object AniobDlpSanitizer {

    // Credit Card candidate matcher (13-19 digits, optionally spaced or hyphenated)
    private val CARD_CANDIDATE_REGEX = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    // Indian Aadhaar: exactly 12 digits, first digit 2-9, formatted in 4-4-4
    private val AADHAAR_REGEX = Regex("(?<!\\d)[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![\\s-]?\\d)")

    // Indian PAN: 5 uppercase letters, 4 digits, 1 uppercase letter
    private val PAN_REGEX = Regex("(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])")

    // US Social Security Number: AAA-GG-SSSS format with validation of non-zero groups
    private val SSN_REGEX = Regex("(?<!\\d)(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?!\\d)")

    // Bearer / OAuth tokens
    private val BEARER_TOKEN_REGEX = Regex("(?i)\\b(bearer\\s+)([A-Za-z0-9\\-_.~+/]+=*)\\b")

    // API Keys and Secrets
    private val API_KEY_REGEX = Regex("(?i)\\b((?:api[_-]?key|client[_-]?secret|auth[_-]?token|access[_-]?token|private[_-]?key)\\s*[:=]\\s*[\"']?)([A-Za-z0-9_\\-]{16,})([\"']?)")

    // Passwords
    private val PASSWORD_REGEX = Regex("(?i)\\b((?:password|passwd|pwd)\\s*[:=]\\s*[\"']?)([^\\s\"']{4,})([\"']?)")

    // One-Time Passwords / Verification PINs preceded by indicator keywords
    private val OTP_KEYWORD_REGEX = Regex("(?i)\\b(?:otp|one[- ]time[- ]password|verification[- ]code|security[- ]pin|auth[- ]code)\\D{1,10}(\\b\\d{4,8}\\b)")

    /**
     * Sanitizes [text] by redacting all detected sensitive PII and secrets.
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text

        var sanitized = text

        // 1. Bearer tokens
        sanitized = BEARER_TOKEN_REGEX.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}[TOKEN_REDACTED]"
        }

        // 2. API Keys & secrets
        sanitized = API_KEY_REGEX.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}[KEY_REDACTED]${matchResult.groupValues[3]}"
        }

        // 3. Passwords
        sanitized = PASSWORD_REGEX.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}[PASSWORD_REDACTED]${matchResult.groupValues[3]}"
        }

        // 4. OTPs with contextual indicators
        sanitized = OTP_KEYWORD_REGEX.replace(sanitized) { matchResult ->
            val full = matchResult.value
            val otpCode = matchResult.groupValues[1]
            full.replace(otpCode, "[OTP_REDACTED]")
        }

        // 5. Credit Cards (Luhn validated, evaluated BEFORE 12-digit Aadhaar to avoid partial match)
        sanitized = CARD_CANDIDATE_REGEX.replace(sanitized) { matchResult ->
            val digits = matchResult.value.filter { it.isDigit() }
            if (digits.length in 13..19 && isValidLuhn(digits)) {
                "[CARD_REDACTED]"
            } else {
                matchResult.value
            }
        }

        // 6. Indian Aadhaar (exactly 12 digits)
        sanitized = AADHAAR_REGEX.replace(sanitized, "[AADHAAR_REDACTED]")

        // 7. US SSN
        sanitized = SSN_REGEX.replace(sanitized, "[SSN_REDACTED]")

        // 8. Indian PAN
        sanitized = PAN_REGEX.replace(sanitized, "[PAN_REDACTED]")

        return sanitized
    }

    /**
     * Returns true if [text] contains any sensitive PII, credential, or token.
     */
    fun containsSensitiveData(text: String): Boolean {
        if (text.isBlank()) return false
        if (BEARER_TOKEN_REGEX.containsMatchIn(text)) return true
        if (API_KEY_REGEX.containsMatchIn(text)) return true
        if (PASSWORD_REGEX.containsMatchIn(text)) return true
        if (OTP_KEYWORD_REGEX.containsMatchIn(text)) return true
        if (SSN_REGEX.containsMatchIn(text)) return true
        if (PAN_REGEX.containsMatchIn(text)) return true
        if (AADHAAR_REGEX.containsMatchIn(text)) return true
        for (match in CARD_CANDIDATE_REGEX.findAll(text)) {
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..19 && isValidLuhn(digits)) {
                return true
            }
        }
        return false
    }

    /**
     * Luhn algorithm for payment card number validation.
     */
    fun isValidLuhn(numberString: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in numberString.length - 1 downTo 0) {
            val char = numberString[i]
            if (!char.isDigit()) return false
            var n = char - '0'
            if (alternate) {
                n *= 2
                if (n > 9) {
                    n -= 9
                }
            }
            sum += n
            alternate = !alternate
        }
        return (sum % 10 == 0)
    }
}
