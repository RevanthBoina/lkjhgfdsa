package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the "scroll until found" recovery used when a valid element sits below the fold and the
 * accessibility tree does not contain it: the agent must scroll a scrollable container and retry
 * rather than reporting element-not-found immediately.
 */
class AniobScrollHelperTest {

    private fun listNode(id: Int, text: String) = AniobNode(
        id = id,
        className = "android.widget.TextView",
        text = text,
        bounds = AniobRect(0, id * 60, 300, id * 60 + 50)
    )

    private fun screenWith(visibleTexts: List<String>, scrollable: Boolean = true) = AniobScreenState(
        packageName = "com.example.app",
        nodes = buildList {
            addAll(visibleTexts.mapIndexed { i, t -> listNode(i + 1, t) })
            if (scrollable) {
                add(AniobNode(id = 99, className = "android.widget.ScrollView", isScrollable = true, bounds = AniobRect(0, 0, 1080, 2000)))
            }
        }
    )

    @Test
    fun `returns immediately when target already visible`() {
        val screen = screenWith(listOf("Search", "Submit"))
        val result = AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { it.text == "Submit" },
            taskPrompt = "tap submit",
            capture = { error("must not capture when already found") },
            executeSwipe = { error("must not swipe when already found") }
        )
        assertTrue(result.found)
        assertEquals(0, result.swipes)
        assertEquals("Submit", result.node?.text)
    }

    @Test
    fun `scrolls down and finds element that was below the fold`() {
        val initial = screenWith(listOf("Search", "Profile"))
        val afterScroll = screenWith(listOf("Profile", "Submit Button"))
        var swipes = 0
        val result = AniobScrollHelper.scrollUntilFound(
            screenState = initial,
            targetPredicate = { it.text.contains("Submit") },
            taskPrompt = "tap submit button",
            capture = { afterScroll },
            executeSwipe = { swipe ->
                swipes++
                assertEquals(SwipeDirection.DOWN, swipe.direction)
                true
            }
        )
        assertTrue(result.found)
        assertEquals("Submit Button", result.node?.text)
        assertEquals(1, swipes)
    }

    @Test
    fun `returns not found when no scrollable container`() {
        val screen = screenWith(listOf("Search"), scrollable = false)
        val result = AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { it.text == "Submit" },
            taskPrompt = "tap submit",
            capture = { screen },
            executeSwipe = { true }
        )
        assertFalse(result.found)
        assertEquals(0, result.swipes)
    }

    @Test
    fun `stops after max swipes when target never appears`() {
        val screen = screenWith(listOf("Search"))
        var swipes = 0
        val result = AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { it.text == "NeverThere" },
            taskPrompt = "tap never",
            capture = { screen },
            executeSwipe = { swipes++; true }
        )
        assertFalse(result.found)
        assertEquals(AniobScrollHelper.MAX_SWIPES, swipes)
    }

    @Test
    fun `aborts early when swipe dispatch fails`() {
        val screen = screenWith(listOf("Search"))
        var swipes = 0
        val result = AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { it.text == "NeverThere" },
            taskPrompt = "tap never",
            capture = { screen },
            executeSwipe = { swipes++; false }
        )
        assertFalse(result.found)
        assertEquals(1, swipes)
    }

    @Test
    fun `up semantic prompt scrolls upward`() {
        val screen = screenWith(listOf("Search"))
        var direction: SwipeDirection? = null
        AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { false },
            taskPrompt = "scroll up to the top",
            capture = { screen },
            executeSwipe = { direction = it.direction; true }
        )
        assertEquals(SwipeDirection.UP, direction)
    }

    @Test
    fun `swipe action targets the scrollable container`() {
        val screen = screenWith(listOf("Search"))
        var containerId: Int? = null
        AniobScrollHelper.scrollUntilFound(
            screenState = screen,
            targetPredicate = { false },
            taskPrompt = "scroll down",
            capture = { screen },
            executeSwipe = {
            val container = (it as AniobAction.Swipe).container
            containerId = (container as? SemanticTarget.SomIndex)?.index
            true
        }
        )
        assertEquals(99, containerId)
    }
}