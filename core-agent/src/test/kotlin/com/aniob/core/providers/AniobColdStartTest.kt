package com.aniob.core.providers

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AniobColdStartTest {
    private class ColdEngine : AniobNativeLlmEngine {
        var ready = false
        var loads = 0
        var generations = 0
        var releases = 0
        var loadedPath: String? = null
        override fun isReady() = ready
        override fun load(path: String): Boolean {
            loads++
            ready = File(path).isFile && File(path).length() > 0
            if (ready) loadedPath = path
            return ready
        }
        override fun generate(prompt: String): String? {
            generations++
            return if (ready) "answer" else null
        }
        override fun release() { ready = false; releases++ }
    }

    private fun withModel(block: (File) -> Unit) {
        val file = File.createTempFile("aniob-cold-", ".litertlm")
        try { file.writeText("fixture"); block(file) } finally { file.delete() }
    }

    @Test fun `cold generation loads before checking readiness`() = withModel { file ->
        val engine = ColdEngine()
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        assertFalse(provider.isAvailable())
        assertEquals(0, engine.loads) // readiness getters must not initialize native memory
        assertTrue(provider.chatStreamingResult("hello") {} is AniobGenerationResult.Ready)
        assertEquals(1, engine.loads)
        assertEquals(1, engine.generations)
    }

    @Test fun `preparation makes both answer and action wrappers available`() = withModel { file ->
        val engine = ColdEngine()
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        val actions = AniobLocalLlmEngineImpl(provider)
        assertFalse(actions.isModelLoaded())
        assertTrue(provider.prepare())
        assertTrue(provider.isAvailable())
        assertTrue(actions.isModelLoaded())
        assertEquals("answer", provider.chatStreaming("hello") {})
        assertTrue(actions.generateStepResult("system", "user") is AniobGenerationResult.Ready)
        assertEquals(1, engine.loads)
        assertEquals(2, engine.generations)
    }

    @Test fun `missing model fails closed then recovers after download`() = withModel { file ->
        file.delete()
        val engine = ColdEngine()
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        assertFalse(provider.prepare())
        assertEquals(AniobGenerationResult.ModelUnavailable, provider.chatStreamingResult("hello") {})
        assertFalse(provider.capabilities().canDriveActions)
        assertEquals(0, engine.generations)
        file.writeText("downloaded fixture")
        assertTrue(provider.prepare())
        assertTrue(provider.isAvailable())
    }

    @Test fun `release is shared and explicit preparation reloads`() = withModel { file ->
        val engine = ColdEngine()
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        val actions = AniobLocalLlmEngineImpl(provider)
        assertTrue(provider.prepare())
        actions.release()
        assertFalse(provider.isAvailable())
        assertFalse(actions.isModelLoaded())
        assertTrue(provider.prepare())
        assertTrue(actions.isModelLoaded())
        assertEquals(2, engine.loads)
        assertEquals(1, engine.releases)
    }

    @Test fun `switch releases previous model even when replacement is missing`() = withModel { first ->
        withModel { second ->
            val engine = ColdEngine()
            val provider = AniobLiteRtProvider(first.path, false) { engine }
            assertTrue(provider.prepare())
            assertTrue(provider.prepare(second.path))
            assertEquals(second.path, engine.loadedPath)
            assertEquals(1, engine.releases)
            assertFalse(provider.prepare(second.path + ".missing"))
            assertFalse(provider.isAvailable())
            assertEquals(2, engine.releases)
        }
    }

    @Test fun `native load failure never generates or advertises action capability`() = withModel { file ->
        val engine = object : AniobNativeLlmEngine {
            override fun isReady() = false
            override fun load(path: String) = false
            override fun generate(prompt: String): String? = error("must not generate")
            override fun release() = Unit
        }
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        assertFalse(provider.prepare())
        assertFalse(provider.capabilities().canDriveActions)
        assertEquals(AniobGenerationResult.ModelUnavailable, provider.chatStreamingResult("hello") {})
    }

    @Test fun `release waits for native generation and suppresses its stale result`() = withModel { file ->
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var closed = false
        val engine = object : AniobNativeLlmEngine {
            var ready = false
            override fun isReady() = ready
            override fun load(path: String): Boolean { ready = true; return true }
            override fun generate(prompt: String): String {
                entered.countDown()
                check(finish.await(3, TimeUnit.SECONDS))
                assertFalse(closed)
                return "stale"
            }
            override fun release() { closed = true; ready = false }
        }
        val provider = AniobLiteRtProvider(file.path, false) { engine }
        val deltas = StringBuilder()
        val caller = Thread { provider.chatStreaming("hello") { deltas.append(it) } }
        caller.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val releaser = Thread { provider.release() }
        releaser.start()
        // release marks unavailable before waiting for the native lock.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (provider.isAvailable() && System.nanoTime() < deadline) Thread.yield()
        assertFalse(provider.isAvailable())
        finish.countDown()
        caller.join(3000); releaser.join(3000)
        assertFalse(caller.isAlive); assertFalse(releaser.isAlive)
        assertTrue(closed)
        assertEquals("", deltas.toString())
    }
}
