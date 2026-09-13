package com.aniob.core

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.optimizer.AniobTokenOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobTokenOptimizerTest {

    @Test
    fun testMaxSixtyNodesCapped() {
        val rawNodes = (1..100).map { i ->
            AniobNode(
                id = i,
                className = "android.widget.Button",
                text = "Button $i",
                isClickable = true,
                bounds = AniobRect(0, i * 20, 200, i * 20 + 15)
            )
        }

        val optimized = AniobTokenOptimizer.optimize(rawNodes)
        assertEquals(60, optimized.size)
        // Verify sequential Set-of-Mark 1..60 indexing
        assertEquals(1, optimized.first().id)
        assertEquals(60, optimized.last().id)
    }

    @Test
    fun testFiltersInvisibleAndEmptyNodes() {
        val mixedNodes = listOf(
            AniobNode(id = 1, className = "View", isVisibleToUser = false),
            AniobNode(id = 2, className = "View", bounds = AniobRect(0, 0, 0, 0)),
            AniobNode(id = 3, className = "LinearLayout", text = "", isClickable = false),
            AniobNode(id = 4, className = "TextView", text = "Valid Visible Text", bounds = AniobRect(10, 10, 200, 50))
        )

        val optimized = AniobTokenOptimizer.optimize(mixedNodes)
        assertEquals(1, optimized.size)
        assertEquals("Valid Visible Text", optimized[0].text)
        assertEquals(1, optimized[0].id)
    }
}
