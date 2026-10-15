package com.aniob.app.model

import android.util.Log
import com.aniob.core.providers.AniobNativeLlmEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Real on-device LLM engine backed by Google AI Edge LiteRT-LM.
 *
 * This is the implementation that RUNTIME_DECISION.md Option A called for and that was never
 * written. Until now the only `AniobNativeLlmEngine` in the tree was
 * `com.aniob.core.providers.AniobLiteRtEngine`, which declares `private external fun
 * nativeLoadModel/nativeGenerate/nativeRelease` and calls `System.loadLibrary("litertlm")` — but
 * the project bundled no `liblitertlm.so`, no CMakeLists.txt, no C++ source and no litert
 * dependency, so that call threw `UnsatisfiedLinkError` on every device, forever. Verified against
 * a freshly built `app-debug.apk`: its only two native libraries were
 * `lib/arm64-v8a/libandroidx.graphics.path.so` and `lib/arm64-v8a/libdatastore_shared_counter.so`.
 *
 * LiteRT-LM ships as a Kotlin API over a prebuilt AAR, so there is no hand-written JNI here: the
 * `.so` comes from the artifact and this class only drives it.
 *
 * Placement follows AGENTS.md — native runtime drivers live in `:app` behind `core-agent` domain
 * contracts, keeping `core-agent` pure JVM (Guard 3 forbids `import android.` there). `AniobLiteRtEngine`
 * in `core-agent` is deliberately left untouched: it is the JVM-testable seam the 311-test
 * `:core-agent` suite exercises without an Android device.
 *
 * Threading: [load] and [generate] are blocking and must be called from a background dispatcher.
 * Conversation objects are single-use and not safe to share across threads, so [generate] creates
 * and closes one per call while the (expensive) [Engine] is reused.
 */
class AniobLiteRtLmEngine(
    /** Directory LiteRT-LM may use for its compiled-kernel cache. Must be app-private. */
    private val cacheDir: java.io.File,
    /**
     * Maximum tokens (prompt + generated) the engine reserves. Sized for Aniob's planner/executor
     * prompts, not for long-form chat; the value is clamped to what the model file supports.
     */
    private val maxNumTokens: Int = 4096,
    /**
     * CPU only, by default.
     *
     * GPU is deliberately not attempted here. The GPU backend fails in device-specific ways
     * (missing/incompatible OpenCL drivers) and this change was verified only by compilation and
     * packaging — no emulator or device was available, so a GPU-first path would be an untested
     * claim. CPU is the conservative default that works without a driver dependency; see
     * `preferGpu` for opting in once GPU behaviour has been measured on real hardware.
     */
    private val preferGpu: Boolean = false,
    /**
     * System instruction applied to every conversation this engine opens.
     *
     * `AniobNativeLlmEngine.generate` takes only the user prompt, so the instruction is fixed at
     * construction. LiteRT-LM's `ConversationConfig` accepts it there, which keeps the `core-agent`
     * contract unchanged.
     */
    private val systemPrompt: String = "",
    private val sampler: SamplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.3)
) : AniobNativeLlmEngine {

    private val engineLock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null

    override fun isReady(): Boolean = engine != null

    /**
     * Loads the `.litertlm` model at [modelPath] (absolute), reusing an already-loaded engine when
     * the same file is requested again. Model initialisation costs seconds, so callers should load
     * once and hold the engine rather than reloading per prompt.
     *
     * @return true when the engine is initialised and ready to generate.
     */
    override fun load(modelPath: String): Boolean = synchronized(engineLock) {
        // Fast path: already loaded from this exact file.
        engine?.let { if (loadedPath == modelPath) return true }

        val f = java.io.File(modelPath)
        if (!f.exists() || f.length() == 0L) {
            Log.w(TAG, "model file missing or empty, refusing to initialise engine: $modelPath")
            return false
        }

        return try {
            // A different model was loaded before: release it first. Engines hold hundreds of MB
            // of native memory, so two must not coexist on a phone.
            release()

            val backend = if (preferGpu) Backend.GPU() else Backend.CPU()
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxNumTokens,
                cacheDir = cacheDir.absolutePath
            )
            val created = Engine(config).also { it.initialize() }
            engine = created
            loadedPath = modelPath
            Log.i(TAG, "LiteRT-LM engine ready: ${f.name} backend=${if (preferGpu) "GPU" else "CPU"}")
            true
        } catch (e: Throwable) {
            // Includes UnsatisfiedLinkError if the native library is ever stripped from the build,
            // and OOM on low-RAM devices. Degrade to "not ready" so AniobHybridEngineRouter can fall
            // back to cloud instead of crashing the app.
            Log.e(TAG, "LiteRT-LM engine failed to load $modelPath: ${e.javaClass.simpleName}: ${e.message}")
            engine = null
            loadedPath = null
            false
        }
    }

    /**
     * Runs one turn: the constructor's [systemPrompt] becomes the conversation's system instruction
     * and [prompt] the user message. Returns the trimmed completion, or null when the engine is not
     * ready or the call failed — matching the contract's nullable return so callers treat failure as
     * "fall back to cloud" rather than an exception.
     */
    override fun generate(prompt: String): String? = synchronized(engineLock) {
        val eng = engine ?: run {
            Log.w(TAG, "generate() called before a successful load(); returning null")
            return null
        }
        var conversation: Conversation? = null
        return try {
            conversation = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = sampler
                )
            )
            // sendMessage returns a Message wrapper, not a String; the text lives in its Contents.
            val response = conversation.sendMessage(prompt)
            response.contents?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.e(TAG, "LiteRT-LM generation failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            // Always release the conversation's KV cache, even on failure.
            try { conversation?.close() } catch (e: Throwable) { Log.w(TAG, "conversation close failed: ${e.message}") }
        }
    }

    /** Releases native model memory. Safe to call repeatedly and before any [load]. */
    override fun release() = synchronized(engineLock) {
        val eng = engine
        engine = null
        loadedPath = null
        if (eng != null) {
            try { eng.close() } catch (e: Throwable) { Log.w(TAG, "engine close failed: ${e.message}") }
        }
    }

    private companion object {
        const val TAG = "AniobLiteRtLm"
    }
}
