package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicVerifierTest {

    private fun screen(nodeCount: Int, treeHash: String, pkg: String = "com.example") =
        AniobScreenState(
            packageName = pkg,
            activityName = "MainActivity",
            nodes = List(nodeCount) { i ->
                AniobNode(id = i, className = "TextView", text = "Item $i", bounds = AniobRect(0, i * 50, 200, i * 50 + 40))
            },
            treeHash = treeHash
        )

    @Test
    fun `tap with identical treeHash reports isExpected false and no-effect reason`() {
        val before = screen(nodeCount = 2, treeHash = "abc123")
        val after = screen(nodeCount = 2, treeHash = "abc123")

        val result = DeterministicVerifier.verify(
            AniobAction.Tap(SemanticTarget.SomIndex(1)),
            before,
            after
        )

        assertFalse("Identical treeHash must be no-effect", result.isExpected)
        assertTrue("Reason must mention no-effect", result.reason.contains("No-effect"))
        assertFalse("isSuccessful alias mirrors primary", result.isSuccessful)
    }

    @Test
    fun `tap with changed treeHash reports isExpected true`() {
        val before = screen(nodeCount = 2, treeHash = "abc123")
        val after = screen(nodeCount = 2, treeHash = "def456")

        val result = DeterministicVerifier.verify(AniobAction.Tap(SemanticTarget.SomIndex(1)), before, after)

        assertTrue(result.isExpected)
        assertTrue(result.reason.contains("transitioned"))
    }

    @Test
    fun `long press and wait also report no-effect when treeHash unchanged`() {
        val before = screen(nodeCount = 1, treeHash = "same")
        val after = screen(nodeCount = 1, treeHash = "same")

        val press = DeterministicVerifier.verify(AniobAction.LongPress(SemanticTarget.SomIndex(0), durationMs = 800), before, after)
        assertFalse(press.isExpected)
        assertTrue(press.reason.contains("No-effect"))

        val wait = DeterministicVerifier.verify(AniobAction.Wait(durationMs = 500), before, after)
        assertFalse(wait.isExpected)
        assertTrue(wait.reason.contains("No-effect"))
    }

    @Test
    fun `open app transitions to target package is expected even with same hash`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val afterSameHash = screen(nodeCount = 1, treeHash = "x", pkg = "com.android.settings")

        val result = DeterministicVerifier.verify(
            AniobAction.OpenApp(packageName = "com.android.settings"),
            before,
            afterSameHash
        )

        assertTrue("Package change counts as an expected transition", result.isExpected)
    }

    @Test
    fun `wrong app open is not expected`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val wrongAfter = screen(nodeCount = 1, treeHash = "y", pkg = "com.wrong.app")

        val result = DeterministicVerifier.verify(
            AniobAction.OpenApp(packageName = "com.android.settings"),
            before,
            wrongAfter
        )

        assertFalse("Wrong package open must fail verification", result.isExpected)
    }

    @Test
    fun `unrelated text field update is not expected`() {
        val targetNode = AniobNode(id = 1, className = "EditText", text = "Old Text", isEditable = true)
        val otherNode = AniobNode(id = 2, className = "EditText", text = "Hello", isEditable = true)
        val before = AniobScreenState(packageName = "com.app", treeHash = "h1", nodes = listOf(targetNode, otherNode))
        val after = AniobScreenState(packageName = "com.app", treeHash = "h2", nodes = listOf(targetNode, otherNode.copy(text = "Hello World")))

        val result = DeterministicVerifier.verify(
            AniobAction.InputText(target = SemanticTarget.SomIndex(1), text = "Target Input"),
            before,
            after
        )

        assertFalse("Input text not appearing in target node must fail", result.isExpected)
    }

    @Test
    fun `min-step-only finish is not expected without observable evidence`() {
        val criteria = com.aniob.core.domain.SuccessCriteria(minSteps = 2)
        val screen = screen(nodeCount = 2, treeHash = "abc")

        val result = DeterministicVerifier.verifyFinish(criteria, screen, steps = 3)

        assertFalse("minSteps alone must never count as sufficient observable evidence", result.isExpected)
    }

    @Test
    fun `finish with observable criteria satisfied is expected and captures textEvidence`() {
        val criteria = com.aniob.core.domain.SuccessCriteria(
            mustContainText = listOf("Item 0"),
            mustShowPackage = listOf("com.example"),
            minSteps = 1
        )
        val screen = screen(nodeCount = 2, treeHash = "abc", pkg = "com.example")

        val result = DeterministicVerifier.verifyFinish(criteria, screen, steps = 2)

        assertTrue("Observable evidence satisfied must verify", result.isExpected)
        assertNotNull("textEvidence must be populated", result.textEvidence)
        assertTrue("textEvidence must contain matched item", result.textEvidence!!.contains("Item 0"))
        assertTrue("textEvidence must contain matched package", result.textEvidence!!.contains("com.example"))
    }

    @Test
    fun `open app and input text populate textEvidence`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val after = screen(nodeCount = 1, treeHash = "y", pkg = "com.android.settings")

        val openAppRes = DeterministicVerifier.verify(AniobAction.OpenApp("com.android.settings"), before, after)
        assertTrue(openAppRes.isExpected)
        assertEquals("com.android.settings", openAppRes.textEvidence)

        val target = AniobNode(id = 1, className = "EditText", text = "hello world", isEditable = true)
        val screenWithText = AniobScreenState(packageName = "com.android.settings", nodes = listOf(target))
        val inputRes = DeterministicVerifier.verify(
            AniobAction.InputText(SemanticTarget.SomIndex(1), text = "hello"),
            before,
            screenWithText
        )
        assertTrue(inputRes.isExpected)
        assertEquals("hello", inputRes.textEvidence)
    }
}