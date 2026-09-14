package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            AniobAction.Tap(targetNodeId = 1, x = 100, y = 100),
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

        val result = DeterministicVerifier.verify(AniobAction.Tap(targetNodeId = 1), before, after)

        assertTrue(result.isExpected)
        assertTrue(result.reason.contains("transitioned"))
    }

    @Test
    fun `long press and wait also report no-effect when treeHash unchanged`() {
        val before = screen(nodeCount = 1, treeHash = "same")
        val after = screen(nodeCount = 1, treeHash = "same")

        val press = DeterministicVerifier.verify(AniobAction.LongPress(targetNodeId = 0, durationMs = 800), before, after)
        assertFalse(press.isExpected)
        assertTrue(press.reason.contains("No-effect"))

        val wait = DeterministicVerifier.verify(AniobAction.Wait(durationMs = 500), before, after)
        assertFalse(wait.isExpected)
        assertTrue(wait.reason.contains("No-effect"))
    }

    @Test
    fun `open app transitions to target package is expected even with same hash`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val afterSameHash = screen(nodeCount = 1, treeHash = "x", pkg = "com.settings")

        val result = DeterministicVerifier.verify(
            AniobAction.OpenApp(packageName = "com.android.settings"),
            before,
            afterSameHash
        )

        assertTrue("Package change counts as an expected transition", result.isExpected)
    }
}