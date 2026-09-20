package com.aniob.core.providers

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobToolCallParserTest {

    private fun screenOf(vararg ids: Int) = AniobScreenState(
        packageName = "com.android.settings",
        nodes = ids.map { AniobNode(id = it, className = "TextView", text = "n$it") }
    )

    @Test
    fun `parses strict tap tool call with som target`() {
        val action = AniobToolCallParser.parseToAction(
            """{"thought":"Tap search","tool":"tap","target":{"kind":"som_index","value":4}}"""
        )
        assertEquals(AniobAction.Tap(SemanticTarget.SomIndex(4), "Tap search"), action)
    }

    @Test
    fun `parses tool call embedded in prose`() {
        val raw = "Sure, here is the step:\n{\"thought\":\"open settings\",\"tool\":\"open_app\",\"package_name\":\"com.android.settings\"}\nDone."
        val action = AniobToolCallParser.parseToAction(raw)
        assertEquals(AniobAction.OpenApp("com.android.settings", thought = "open settings"), action)
    }

    @Test
    fun `returns null when no tool key`() {
        assertNull(AniobToolCallParser.parseToAction("I think we should search for it"))
        assertNull(AniobToolCallParser.parseToAction(""))
    }

    @Test
    fun `maps tap to action only for existing node`() {
        val action = AniobToolCallParser.toAction(
            """{"thought":"x","tool":"tap","target":{"kind":"som_index","value":7}}""",
            screenOf(1, 2, 3)
        )
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `maps tap to Tap for valid node`() {
        val action = AniobToolCallParser.toAction(
            """{"thought":"x","tool":"tap","target":{"kind":"som_index","value":2}}""",
            screenOf(1, 2, 3)
        )
        assertEquals(SemanticTarget.SomIndex(2), (action as AniobAction.Tap).target)
    }

    @Test
    fun `swipe parses an explicit direction`() {
        val action = AniobToolCallParser.toAction(
            """{"thought":"x","tool":"swipe","direction":"DOWN"}""",
            screenOf(1)
        )
        assertEquals(SwipeDirection.DOWN, (action as AniobAction.Swipe).direction)
    }

    @Test
    fun `swipe without direction fails closed rather than guessing`() {
        val action = AniobToolCallParser.toAction("""{"thought":"x","tool":"swipe"}""", screenOf(1))
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `coordinate fields are rejected by the single parser`() {
        val action = AniobToolCallParser.parseToAction(
            """{"thought":"x","tool":"tap","target":{"kind":"som_index","value":1},"x":100,"y":200}"""
        )
        assertNull(action)
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
            AniobGenerationResult.Ready("""{"thought":"tap","tool":"tap","target":{"kind":"som_index","value":99}}"""),
            screen
        )
        assertTrue(action is AniobAction.Fail)
    }

    @Test
    fun `ready output with valid node resolves to tap`() {
        val action = AniobLocalActionResolver.resolve(
            AniobGenerationResult.Ready("""{"thought":"tap","tool":"tap","target":{"kind":"som_index","value":1}}"""),
            screen
        )
        assertEquals(SemanticTarget.SomIndex(1), (action as AniobAction.Tap).target)
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