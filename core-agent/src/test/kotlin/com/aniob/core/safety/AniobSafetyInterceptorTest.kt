package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobSafetyInterceptorTest {

    private fun screen(pkg: String = "com.aniob.test", hash: String = "H1"): AniobScreenState =
        AniobScreenState(packageName = pkg, treeHash = hash)

    @Test
    fun testPaymentActionBlocked() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Click(targetNodeId = 1, thought = "complete the payment now"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_pay"
        )
        assertFalse(result.isAllowed)
        assertEquals("HIGH", result.riskTier)
    }

    @Test
    fun testActionWithPayInsideWordIsNotBlocked() {
        // "display" contains the letters p-a-y but is not a payment op.
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.InputText(targetNodeId = 3, text = "display the results"),
            targetNode = AniobNode(id = 3, className = "EditText", bounds = AniobRect(0, 0, 10, 10), isEditable = true),
            screenState = screen(),
            screenFingerprint = "fp_display"
        )
        assertTrue(result.isAllowed)
    }

    @Test
    fun testOtpOnEditableNodeBlocked() {
        val editable = AniobNode(id = 2, className = "EditText", bounds = AniobRect(0, 0, 10, 10), isEditable = true)
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.InputText(targetNodeId = 2, text = "my otp is 1234"),
            targetNode = editable,
            screenState = screen(),
            screenFingerprint = "fp_otp"
        )
        assertFalse(result.isAllowed)
        assertTrue(result.reason.contains("Sensitive", ignoreCase = true))
    }

    @Test
    fun testConfirmWithUserUnknownContactRequiresConfirmation() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.ConfirmWithUser(message = "Send message to ???"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_confirm"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        assertEquals("MEDIUM", result.riskTier)
    }

    @Test
    fun testConfirmWithUserKnownContactStillRequiresConfirmation() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.ConfirmWithUser(message = "Send message to Mom"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_confirm2"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun testNormalActionAllowedLowRisk() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(x = 50, y = 60),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_normal"
        )
        assertTrue(result.isAllowed)
        assertEquals("LOW", result.riskTier)
        assertFalse(result.requiresConfirmation)
    }
}