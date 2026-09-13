package com.aniob.core

import com.aniob.core.diff.AniobScreenDiff
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobScreenDiffTest {

    @Test
    fun testLevelOneEventDrivenSkip() {
        val previous = AniobScreenState(packageName = "com.test.app", treeHash = "abc1234")
        val currentNodes = listOf(
            AniobNode(id = 1, className = "TextView", text = "Static", bounds = AniobRect(0, 0, 100, 50))
        )

        val result = AniobScreenDiff.evaluateWaterfall(
            hasAccessibilityContentChanged = false,
            currentNodes = currentNodes,
            previousScreen = previous
        )

        assertEquals(1, result.waterfallLevelUsed)
        assertFalse(result.shouldCaptureFullScreenshot)
        assertTrue(result.isIdenticalToPrevious)
    }

    @Test
    fun testLevelFourOnFirstScreenCapture() {
        val currentNodes = listOf(
            AniobNode(id = 1, className = "TextView", text = "New App", bounds = AniobRect(0, 0, 100, 50))
        )

        val result = AniobScreenDiff.evaluateWaterfall(
            hasAccessibilityContentChanged = true,
            currentNodes = currentNodes,
            previousScreen = null
        )

        assertEquals(4, result.waterfallLevelUsed)
        assertTrue(result.shouldCaptureFullScreenshot)
        assertFalse(result.isIdenticalToPrevious)
    }
}
