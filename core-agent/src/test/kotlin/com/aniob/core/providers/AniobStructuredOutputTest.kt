package com.aniob.core.providers

import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding #3: exactly one parse path, one repair retry, then an honest fallback. */
class AniobStructuredOutputTest {

    private val screen = AniobScreenState(
        packageName = "com.a",
        nodes = listOf(
            AniobNode(
                id = 1,
                className = "android.widget.Button",
                text = "Submit",
                bounds = AniobRect(0, 0, 100, 50),
                isClickable = true
            )
        ),
        treeHash = "h1"
    )

    @Test
    fun `valid json parses without repair`() {
        val raw = """{"thought":"tap it","tool":"TAP","target":{"kind":"text","value":"Submit"}}"""
        val result = AniobStructuredOutput.parseOrRepair(raw, screen)
        assertTrue(result.action is AniobAction.Tap)
        assertFalse(result.repaired)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `unparseable json triggers exactly one repair attempt`() {
        var repairCalls = 0
        val result = AniobStructuredOutput.parseOrRepair(
            raw = "sure, I will click the button now",
            screenState = screen,
            repairCall = {
                repairCalls++
                """{"thought":"tap","tool":"TAP","target":{"kind":"text","value":"Submit"}}"""
            }
        )
        assertEquals(1, repairCalls)
        assertTrue(result.repaired)
        assertTrue(result.action is AniobAction.Tap)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `double failure falls back to a bounded Wait not a guessed tap`() {
        var repairCalls = 0
        val result = AniobStructuredOutput.parseOrRepair(
            raw = "garbage",
            screenState = screen,
            repairCall = { repairCalls++; "still garbage" }
        )
        assertEquals(1, repairCalls)
        assertTrue(result.usedFallback)
        assertTrue("fallback must never move the UI blind", result.action is AniobAction.Wait)
    }

    @Test
    fun `coordinate output is rejected and reported`() {
        val result = AniobStructuredOutput.parseOrRepair(
            raw = """{"tool":"TAP","x":100,"y":200}""",
            screenState = screen
        )
        assertTrue(result.usedFallback)
        assertTrue(result.reason.contains("coordinate"))
    }

    @Test
    fun `missing repair provider degrades to fallback`() {
        val result = AniobStructuredOutput.parseOrRepair(raw = "not json", screenState = screen)
        assertTrue(result.usedFallback)
        assertTrue(result.action is AniobAction.Wait)
    }

    @Test
    fun `hallucinated som index is rejected against the live screen`() {
        val raw = """{"thought":"tap","tool":"TAP","target":{"kind":"som_index","value":"99"}}"""
        val result = AniobStructuredOutput.parseOrRepair(raw, screen, repairCall = { null })
        assertTrue("a SoM index not on the screen must not become a tap", result.action is AniobAction.Fail)
    }

    @Test
    fun `explicit model FAIL is honored as legitimate output`() {
        val raw = """{"thought":"no model","tool":"FAIL","reason":"model unavailable"}"""
        val result = AniobStructuredOutput.parseOrRepair(raw, screen)
        assertTrue(result.action is AniobAction.Fail)
        assertFalse(result.usedFallback)
    }
}