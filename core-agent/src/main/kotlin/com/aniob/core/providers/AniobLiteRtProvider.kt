package com.aniob.core.providers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
 * Generation runs on a worker thread and emits the completed answer through [onDelta].
 * The caller waits at most 15 seconds; native work may outlive interruption, so lifecycle
 * operations are serialized and late results are invalidated.
 */
class AniobLiteRtProvider(
    @Volatile private var modelPath: String = "/data/local/tmp/qwen2.5-1.5b-gpu.bin",
    private var useGpu: Boolean = true,
    private val engineFactory: (String) -> AniobNativeLlmEngine = { path -> AniobLiteRtEngine(path) }
) : AniobLocalLlmProvider {

    private val isEngineLoaded = AtomicBoolean(true)
    private var gpuFailed = false
    private val lifecycleLock = Any()
    private val epoch = AtomicLong()
    private var preparedPath: String? = null

    /** Resolved once on first use so construction stays cheap and testable. */
    private val nativeEngine: AniobNativeLlmEngine? by lazy {
        try {
            engineFactory(modelPath)
        } catch (_: Throwable) {
            null
        }
    }

    /** Blocking: call on a background dispatcher before routing, never from a readiness getter.
     * Switching paths releases the old model first; failure never advertises availability.
     */
    fun prepare(path: String = modelPath): Boolean = synchronized(lifecycleLock) {
        val engine = nativeEngine ?: return@synchronized false
        if (preparedPath == path && isEngineLoaded.get() && engine.isReady()) {
            return@synchronized true
        }
        epoch.incrementAndGet()
        if (preparedPath != null && preparedPath != path) engine.release()
        modelPath = path
        preparedPath = null
        isEngineLoaded.set(false)
        val ready = engine.load(path) && engine.isReady()
        if (ready) preparedPath = path
        isEngineLoaded.set(ready)
        gpuFailed = false
        ready
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
        val engine = nativeEngine ?: return AniobGenerationResult.ModelUnavailable
        // A cold engine is not ready until load succeeds. Preparation is also invoked explicitly
        // by app callers before their routing gates; this covers direct provider callers.
        if (!prepare()) return AniobGenerationResult.ModelUnavailable

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
        val requestEpoch = epoch.get()
        val latch = CountDownLatch(1)
        val fullResponse = StringBuilder()
        var timedOut = false

        val workerThread = Thread {
            try {
                val generated = synchronized(lifecycleLock) {
                    if (!isEngineLoaded.get() || epoch.get() != requestEpoch) return@Thread
                    engine.generate(prompt)
                } ?: return@Thread
                if (!isEngineLoaded.get() || epoch.get() != requestEpoch) return@Thread
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
            epoch.incrementAndGet() // Suppress late results if native work ignores interruption.
            workerThread.interrupt()
        }
        if (timedOut || fullResponse.isEmpty()) return null
        return fullResponse.toString()
    }

    /**
     * Called under memory pressure (e.g., onTrimMemory) to release native buffers.
     */
    override fun release() {
        epoch.incrementAndGet()
        isEngineLoaded.set(false)
        synchronized(lifecycleLock) {
            // A concurrent preparation may have completed while release waited for this lock.
            isEngineLoaded.set(false)
            nativeEngine?.release()
            preparedPath = null
        }
    }

    fun reload() {
        isEngineLoaded.set(true)
        gpuFailed = false
    }

    companion object {
        const val DELTA_THROTTLE_MS = 60L
        const val DELTA_CHUNK_CHARS = 4
        const val GENERATION_TIMEOUT_SECONDS = 180L

        /** Explicit, non-actionable failure contract for absent native engine/model. */
        const val MODEL_UNAVAILABLE_JSON =
            """{"thought":"No local model available","tool":"fail","reason":"Local model engine unavailable - install a model in Models screen or switch to Auto mode"}"""

        fun generationFailedJson(reason: String): String {
            val safeReason = reason.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
            return """{"thought":"Local generation failed","tool":"fail","reason":"$safeReason"}"""
        }
    }
}