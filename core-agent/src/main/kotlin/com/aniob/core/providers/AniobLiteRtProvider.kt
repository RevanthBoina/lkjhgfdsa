package com.aniob.core.providers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interface and configuration for local on-device LLM inference (LiteRT-LM / llama.cpp GGUF).
 * Zero Android dependencies (pure Kotlin JVM contract).
 */
interface AniobLocalLlmProvider {
    fun chatStreaming(
        prompt: String,
        onDelta: (String) -> Unit
    ): String

    fun release()
    fun isAvailable(): Boolean
}

/**
 * On-device SLM provider driving a real native engine ([AniobNativeLlmEngine] or
 * [AniobLlamaCppNativeEngine]).
 *
 * Non-negotiable behaviour:
 * - If [modelPath] does not exist, or the native library/models it cannot load, the provider
 *   returns an explicit offline/failure tool call. It never fabricates a tap on a node id
 *   that may not exist (that produced silent wrong-action failures in production).
 * - Only when a real engine emits output does generation return [AniobGenerationResult.Ready].
 *
 * Streaming keeps the original shape: a worker thread emits 4-char deltas through [onDelta]
 * with a 60ms throttle, while the caller blocks on a 15s [CountDownLatch].
 */
class AniobLiteRtProvider(
    private val modelPath: String = "/data/local/tmp/qwen2.5-1.5b-gpu.bin",
    private var useGpu: Boolean = true,
    private val engineFactory: (String) -> AniobNativeLlmEngine = { path -> AniobLiteRtEngine(path) }
) : AniobLocalLlmProvider {

    private val isEngineLoaded = AtomicBoolean(true)
    private var gpuFailed = false

    /** Resolved once on first use so construction stays cheap and testable. */
    private val nativeEngine: AniobNativeLlmEngine? by lazy {
        try {
            engineFactory(modelPath)
        } catch (_: Throwable) {
            null
        }
    }

    override fun isAvailable(): Boolean {
        val engine = nativeEngine ?: return false
        return isEngineLoaded.get() && engine.isReady()
    }

    fun readiness(): ProviderReadiness {
        if (!java.io.File(modelPath).exists()) {
            return ProviderReadiness.FILE_MISSING
        }
        val engine = nativeEngine ?: return ProviderReadiness.MODEL_FILE_PRESENT
        if (!engine.isReady()) {
            return ProviderReadiness.MODEL_FILE_PRESENT
        }
        if (!isEngineLoaded.get()) {
            return ProviderReadiness.ENGINE_LOADABLE
        }
        return if (capabilities().canDriveActions) {
            ProviderReadiness.ACTION_CAPABLE
        } else {
            ProviderReadiness.ENGINE_LOADED
        }
    }

    /**
     * A loaded native engine is a real action driver only when it is actually available; a
     * provider constructed around a missing library reports [AniobEngineCapabilities.canDriveActions]
     * false so the loop quarantines it instead of labelling its output LOCAL_SLM.
     */
    fun capabilities(): AniobEngineCapabilities = if (isAvailable()) {
        AniobEngineCapabilities(canAnswer = true, canDriveActions = true, reason = "Native engine loaded")
    } else {
        AniobEngineCapabilities(canAnswer = false, canDriveActions = false, reason = "No usable native engine/model")
    }

    /**
     * Explicit generation result. Callers that decide what to do (agent loop) should use this;
     * [chatStreaming] is the string-compatible convenience wrapper.
     */
    fun chatStreamingResult(
        prompt: String,
        onDelta: (String) -> Unit
    ): AniobGenerationResult {
        if (!isEngineLoaded.get()) {
            return AniobGenerationResult.GenerationFailed("LiteRT engine released or uninitialized")
        }
        val engine = nativeEngine
        if (engine == null || !engine.isReady()) {
            return AniobGenerationResult.ModelUnavailable
        }
        if (!engine.load(modelPath)) {
            return AniobGenerationResult.GenerationFailed("Native loadModel failed for $modelPath")
        }

        // GPU path first; retry once without the accelerator on any failure.
        val first = executeStreamingInternal(engine, prompt, onDelta)
        if (first != null) return AniobGenerationResult.Ready(first)

        if (useGpu && !gpuFailed) {
            gpuFailed = true
            val retry = executeStreamingInternal(engine, prompt, onDelta)
            if (retry != null) return AniobGenerationResult.Ready(retry)
        }
        return AniobGenerationResult.GenerationFailed("Native generate returned no output")
    }

    override fun chatStreaming(
        prompt: String,
        onDelta: (String) -> Unit
    ): String = when (val result = chatStreamingResult(prompt, onDelta)) {
        is AniobGenerationResult.Ready -> result.fullText
        AniobGenerationResult.ModelUnavailable -> MODEL_UNAVAILABLE_JSON
        is AniobGenerationResult.GenerationFailed -> generationFailedJson(result.reason)
        is AniobGenerationResult.Unparseable -> result.rawText
    }

    /**
     * Runs one native generation attempt on a worker thread, emitting throttled deltas.
     * @return the full generated text, or null when generation failed / timed out.
     */
    private fun executeStreamingInternal(
        engine: AniobNativeLlmEngine,
        prompt: String,
        onDelta: (String) -> Unit
    ): String? {
        val latch = CountDownLatch(1)
        val fullResponse = StringBuilder()
        var timedOut = false

        val workerThread = Thread {
            try {
                val generated = engine.generate(prompt) ?: return@Thread
                if (!isEngineLoaded.get()) return@Thread
                fullResponse.append(generated)
                onDelta(generated)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                latch.countDown()
            }
        }
        workerThread.start()

        val completed = latch.await(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            timedOut = true
            workerThread.interrupt()
        }
        if (timedOut || fullResponse.isEmpty()) return null
        return fullResponse.toString()
    }

    /**
     * Called under memory pressure (e.g., onTrimMemory) to release native buffers.
     */
    override fun release() {
        isEngineLoaded.set(false)
        nativeEngine?.release()
    }

    fun reload() {
        isEngineLoaded.set(true)
        gpuFailed = false
    }

    companion object {
        const val DELTA_THROTTLE_MS = 60L
        const val DELTA_CHUNK_CHARS = 4
        const val GENERATION_TIMEOUT_SECONDS = 15L

        /** Explicit, non-actionable failure contract for absent native engine/model. */
        const val MODEL_UNAVAILABLE_JSON =
            """{"thought":"No local model available","tool":"fail","reason":"Local model engine unavailable - install a model in Models screen or switch to Auto mode"}"""

        fun generationFailedJson(reason: String): String =
            """{"thought":"Local generation failed","tool":"fail","reason":"$reason"}"""
    }
}