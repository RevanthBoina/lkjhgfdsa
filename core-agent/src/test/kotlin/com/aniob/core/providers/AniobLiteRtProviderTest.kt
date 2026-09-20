package com.aniob.core.providers

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobToolCallParserTest {

    @Test
    fun `parses strict tap tool call`() {
        val call = AniobToolCallParser.parse("""{"thought":"Tap search","tool":"tap","target_node_id":4}""")
        assertEquals("tap", call?.tool)
        assertEquals(4, call?.targetNodeId)
        assertEquals("Tap search", call?.thought)
    }

    @Test
    fun `parses tool call embedded in prose`() {
        val raw = "Sure, here is the step:\n{\"thought\":\"open settings\",\"tool\":\"open_app\",\"package_name\":\"com.android.settings\"}\nDone."
        val call = AniobToolCallParser.parse(raw)
        assertEquals("open_app", call?.tool)
        assertEquals("com.android.settings", call?.packageName)
    }

    @Test
    fun `returns null when no tool key`() {
        assertNull(AniobToolCallParser.parse("I think we should search for it"))
        assertNull(AniobToolCallParser.parse(""))
    }

    @Test
    fun `maps tap to action only for existing node`() {
        val call = AniobToolCallParser.parse("""{"thought":"x","tool":"tap","target_node_id":7}""")!!
        val action = AniobToolCallParser.toAction(call, validNodeIds = setOf(1, 2, 3))
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `maps tap to click for valid node`() {
        val call = AniobToolCallParser.parse("""{"thought":"x","tool":"tap","target_node_id":2}""")!!
        val action = AniobToolCallParser.toAction(call, validNodeIds = setOf(1, 2, 3))
        assertEquals(2, (action as AniobAction.Click).targetNodeId)
    }

    @Test
    fun `swipe direction defaults to down`() {
        val call = AniobToolCallParser.parse("""{"thought":"x","tool":"swipe"}""")!!
        assertEquals(SwipeDirection.DOWN, (AniobToolCallParser.toAction(call) as AniobAction.Swipe).direction)
    }
}

class AniobLocalActionResolverTest {

    private val screen = AniobScreenState(
        packageName = "com.android.settings",
        nodes = listOf(AniobNode(id = 1, className = "TextView", text = "Wi-Fi"))
    )

    @Test
    fun `model unavailable yields actionable failure not a tap`() {
        val action = AniobLocalActionResolver.resolve(AniobGenerationResult.ModelUnavailable, screen)
        assertTrue(action is AniobAction.Fail)
        assertTrue((action as AniobAction.Fail).reason.contains("install a model", ignoreCase = true))
    }

    @Test
    fun `generation failure yields failure with reason`() {
        val action = AniobLocalActionResolver.resolve(
            AniobGenerationResult.GenerationFailed("timed out after 15s"),
            screen
        )
        assertTrue(action is AniobAction.Fail)
        assertTrue((action as AniobAction.Fail).reason.contains("timed out"))
    }

    @Test
    fun `unparseable output never becomes an action`() {
        val action = AniobLocalActionResolver.resolve(AniobGenerationResult.Unparseable("hmm"), screen)
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `ready output with hallucinated node id fails instead of tapping`() {
        val action = AniobLocalActionResolver.resolve(
            AniobGenerationResult.Ready("""{"thought":"tap","tool":"tap","target_node_id":99}"""),
            screen
        )
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `ready output with valid node resolves to click`() {
        val action = AniobLocalActionResolver.resolve(
            AniobGenerationResult.Ready("""{"thought":"tap","tool":"tap","target_node_id":1}"""),
            screen
        )
        assertEquals(1, (action as AniobAction.Click).targetNodeId)
    }
}

class AniobLiteRtProviderTest {

    private class FakeEngine(
        private val ready: Boolean,
        private val output: String?
    ) : AniobNativeLlmEngine {
        var loads = 0
        var generates = 0
        var releases = 0
        override fun isReady(): Boolean = ready
        override fun load(path: String): Boolean {
            loads++
            return ready
        }

        override fun generate(prompt: String): String? {
            generates++
            return output
        }

        override fun release() {
            releases++
        }
    }

    @Test
    fun `missing model reports unavailable and never generates a fake action`() {
        val engine = FakeEngine(ready = false, output = null)
        val provider = AniobLiteRtProvider(engineFactory = { engine })
        val result = provider.chatStreamingResult("prompt") {}
        assertEquals(AniobGenerationResult.ModelUnavailable, result)
        assertEquals(0, engine.generates)
        val text = provider.chatStreaming("prompt") {}
        assertTrue(text.contains("\"tool\":\"fail\""))
    }

    @Test
    fun `real generation streams deltas and returns ready`() {
        val engine = FakeEngine(ready = true, output = "abcdefghij")
        val provider = AniobLiteRtProvider(engineFactory = { engine })
        val deltas = StringBuilder()
        val result = provider.chatStreamingResult("prompt") { deltas.append(it) }
        assertTrue(result is AniobGenerationResult.Ready)
        assertEquals("abcdefghij", deltas.toString())
    }

    @Test
    fun `null generation triggers one gpu retry then fails`() {
        val engine = FakeEngine(ready = true, output = null)
        val provider = AniobLiteRtProvider(engineFactory = { engine })
        val result = provider.chatStreamingResult("prompt") {}
        assertTrue(result is AniobGenerationResult.GenerationFailed)
        assertEquals(2, engine.generates)
    }

    @Test
    fun `release marks engine unavailable`() {
        val engine = FakeEngine(ready = true, output = "x")
        val provider = AniobLiteRtProvider(engineFactory = { engine })
        provider.release()
        assertFalse(provider.isAvailable())
        assertEquals(1, engine.releases)
    }
}