package com.aniob.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobDlpSanitizerTest {

    @Test
    fun testCreditCardMasking() {
        // Valid Visa test card (passes Luhn)
        val text = "My card number is 4532 0150 0000 0007, please charge it."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("My card number is [CARD_REDACTED], please charge it.", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testNonLuhnNumbersPreserved() {
        // 16-digit random number that fails Luhn
        val text = "Order ID 1234567890123456 confirmed."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("Order ID 1234567890123456 confirmed.", sanitized)
    }

    @Test
    fun testAadhaarMasking() {
        val text = "Aadhaar number: 3675 9834 6012."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("Aadhaar number: [AADHAAR_REDACTED].", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testPanMasking() {
        val text = "Taxpayer PAN is ABCDE1234F for filing."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("Taxpayer PAN is [PAN_REDACTED] for filing.", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testSsnMasking() {
        val text = "User SSN is 123-45-6789 on file."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("User SSN is [SSN_REDACTED] on file.", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testBearerTokenMasking() {
        val text = "Authorization: Bearer ya29.a0AfH6SMCxyz123_abc-def."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("Authorization: Bearer [TOKEN_REDACTED].", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testApiKeyMasking() {
        val text = "api_key=\"AIzaSyD-1234567890abcdefghijklmnopqrstuvwxyz\""
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("api_key=\"[KEY_REDACTED]\"", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testPasswordMasking() {
        val text = "Connecting with password: SecretP@ssw0rd! to server."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals("Connecting with password: [PASSWORD_REDACTED] to server.", sanitized)
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testOtpMasking() {
        val text = "Your verification code is 849201. Do not share your OTP."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertTrue(sanitized.contains("[OTP_REDACTED]"))
        assertTrue(AniobDlpSanitizer.containsSensitiveData(text))
    }

    @Test
    fun testCleanTextUntouched() {
        val text = "Open Settings and scroll to Battery section."
        val sanitized = AniobDlpSanitizer.sanitize(text)
        assertEquals(text, sanitized)
        assertFalse(AniobDlpSanitizer.containsSensitiveData(text))
    }
}
