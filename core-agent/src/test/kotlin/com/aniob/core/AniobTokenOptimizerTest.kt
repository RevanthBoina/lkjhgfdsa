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

    @Test
    fun testFiltersTinyBoundsAndPureLayoutContainers() {
        val nodes = listOf(
            // Zero/tiny-area artifacts are eliminated (area 30 <= 50)
            AniobNode(id = 1, className = "android.widget.ImageButton", isClickable = true, bounds = AniobRect(0, 0, 5, 6)),
            // Pure layout container with no interaction and no content -> pruned
            AniobNode(id = 2, className = "android.widget.FrameLayout", text = "", isClickable = false, bounds = AniobRect(0, 0, 200, 200)),
            // Clickable icon with area > 50 and no text -> fovea keeps it (informative)
            AniobNode(id = 3, className = "android.widget.ImageButton", isClickable = true, contentDescription = "Add", bounds = AniobRect(0, 0, 60, 100))
        )

        val optimized = AniobTokenOptimizer.optimize(nodes)
        assertEquals(1, optimized.size)
        assertEquals("android.widget.ImageButton", optimized[0].className)
        assertEquals("Add", optimized[0].contentDescription)
    }

    @Test
    fun testSortedByTopThenLeftAndReindexed() {
        val nodes = listOf(
            AniobNode(id = 1, className = "android.widget.Button", text = "Low", isClickable = true, bounds = AniobRect(50, 300, 100, 320)),
            AniobNode(id = 2, className = "android.widget.Button", text = "Right-Top", isClickable = true, bounds = AniobRect(300, 10, 350, 30)),
            AniobNode(id = 3, className = "android.widget.Button", text = "Left-Top", isClickable = true, bounds = AniobRect(10, 10, 60, 30))
        )

        val optimized = AniobTokenOptimizer.optimize(nodes)
        assertEquals(listOf("Left-Top", "Right-Top", "Low"), optimized.map { it.text })
        assertEquals(listOf(1, 2, 3), optimized.map { it.id })
    }

    @Test
    fun testToOptimizedTokenStringMatchesPinFormat() {
        val node = AniobNode(
            id = 7,
            className = "android.widget.Button",
            text = "Search",
            contentDescription = "Magnify",
            bounds = AniobRect(100, 200, 300, 250),
            isClickable = true
        )

        val s = node.toOptimizedTokenString()
        assertEquals(
            "[7] android.widget.Button text='Search' contentDesc='Magnify' bounds=[100,200,300,250] clickable=true",
            s
        )
    }

    @Test
    fun testBuildOptimizedNodePromptIncludesSoMIndexAndMax() {
        val nodes = listOf(
            AniobNode(id = 1, className = "android.widget.Button", text = "A", bounds = AniobRect(0, 0, 50, 50), isClickable = true),
            AniobNode(id = 2, className = "android.widget.TextView", text = "B", bounds = AniobRect(0, 60, 50, 110))
        )

        val prompt = AniobTokenOptimizer.buildOptimizedNodePrompt(nodes)
        assertTrue(prompt.contains("Actionable Screen Elements (Total: 2, Max: 60):"))
        assertTrue(prompt.contains("[1] android.widget.Button text='A'"))
        assertTrue(prompt.contains("[2] android.widget.TextView text='B'"))
    }

    @Test
    fun testDistinctByBoundsAndText() {
        val nodes = listOf(
            AniobNode(id = 1, className = "android.widget.TextView", text = "Dup", bounds = AniobRect(0, 0, 100, 50)),
            AniobNode(id = 2, className = "android.widget.TextView", text = "Dup", bounds = AniobRect(0, 0, 100, 50)),
            AniobNode(id = 3, className = "android.widget.TextView", text = "Other", bounds = AniobRect(0, 60, 100, 110))
        )

        val optimized = AniobTokenOptimizer.optimize(nodes)
        assertEquals(2, optimized.size)
        assertEquals(listOf("Dup", "Other"), optimized.map { it.text })
    }
}
