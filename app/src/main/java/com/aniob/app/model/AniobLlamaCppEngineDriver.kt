package com.aniob.app.model

import android.util.Log
import com.aniob.core.providers.AniobNativeLlmEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated experimental llama.cpp backend for Aniob (Phase 3).
 *
 * Implements [AniobNativeLlmEngine] to evaluate GGUF candidate models alongside LiteRT-LM.
 *
 * Pinning & Configuration:
 * - Pinned upstream commit: b3950 (or b4700 / git 6f13b63)
 * - Target architecture: ARM64-v8a with KleidiAI kernels & ARMv8.2-A DotProd
 * - Mutual exclusion: Uses [OnDeviceEngineLock] so LiteRT-LM and llama.cpp NEVER coexist in RAM
 * - Non-blocking: Must be called from background dispatcher (Dispatchers.IO)
 * - Model-specific chat templates & EOS tokens for Gemma 4 and Qwen 3.5
 */
class AniobLlamaCppEngineDriver(
    @Volatile private var modelPath: String = "",
    val modelArchitecture: ModelArchitecture = ModelArchitecture.AUTO,
    private val threads: Int = 4,
    private val contextTokens: Int = 2048,
    private val simulatedGenerator: ((String) -> String)? = null
) : AniobNativeLlmEngine {

    enum class ModelArchitecture {
        GEMMA_4,
        QWEN_3_5,
        AUTO
    }

    private val engineLock = Any()
    private val isLoaded = AtomicBoolean(false)
    private val generationEpoch = AtomicLong(0)

    @Volatile
    private var nativeLibraryLoaded: Boolean = false

    init {
        nativeLibraryLoaded = try {
            System.loadLibrary(LLAMA_LIBRARY)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun isReady(): Boolean = synchronized(engineLock) {
        (nativeLibraryLoaded || simulatedGenerator != null) &&
            modelPath.isNotBlank() &&
            (File(modelPath).exists() || simulatedGenerator != null) &&
            isLoaded.get()
    }

    override fun load(path: String): Boolean = synchronized(engineLock) {
        val f = File(path)
        if (!f.exists() && simulatedGenerator == null) {
            Log.w(TAG, "GGUF model file not found: $path")
            return false
        }

        // Enforce process-wide mutual exclusion: release any active engine (e.g. LiteRT) first
        OnDeviceEngineLock.claimExclusive(this, "llama.cpp GGUF ($path)")

        modelPath = path
        generationEpoch.incrementAndGet()

        if (simulatedGenerator != null) {
            isLoaded.set(true)
            Log.i(TAG, "llama.cpp simulated test harness loaded: ${f.name}")
            return true
        }

        if (!nativeLibraryLoaded) {
            Log.w(TAG, "libllama.so is not bundled or loadable on this device")
            isLoaded.set(false)
            return false
        }

        return try {
            val ok = nativeLoadModel(path, threads, contextTokens)
            isLoaded.set(ok)
            if (ok) {
                Log.i(TAG, "llama.cpp engine successfully loaded: ${f.name} threads=$threads ctx=$contextTokens")
            } else {
                Log.e(TAG, "nativeLoadModel returned false for $path")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load GGUF model via llama.cpp: ${t.message}")
            isLoaded.set(false)
            false
        }
    }

    override fun generate(prompt: String): String? = synchronized(engineLock) {
        if (!isReady()) {
            Log.w(TAG, "generate() called before load() completed or engine ready")
            return null
        }

        val currentEpoch = generationEpoch.get()
        val formattedPrompt = formatPromptForArchitecture(prompt, resolveArchitecture())
        val t0 = System.currentTimeMillis()

        Log.i(TAG, "generate() start: chars=${formattedPrompt.length}")

        if (simulatedGenerator != null) {
            val output = simulatedGenerator.invoke(formattedPrompt)
            val dt = System.currentTimeMillis() - t0
            Log.i(TAG, "generate() finished in ${dt}ms, chars=${output.length}")
            return output.takeIf { generationEpoch.get() == currentEpoch && it.isNotBlank() }
        }

        return try {
            val raw = nativeGenerate(formattedPrompt)
            val dt = System.currentTimeMillis() - t0
            Log.i(TAG, "generate() finished in ${dt}ms, chars=${raw?.length ?: 0}")

            if (generationEpoch.get() != currentEpoch) {
                Log.w(TAG, "Generation result discarded due to cancellation / epoch mismatch")
                return null
            }

            cleanModelOutput(raw, resolveArchitecture())
        } catch (t: Throwable) {
            Log.e(TAG, "llama.cpp generation error: ${t.message}")
            null
        }
    }

    override fun release() {
        synchronized(engineLock) {
            generationEpoch.incrementAndGet()
            if (isLoaded.getAndSet(false)) {
                if (nativeLibraryLoaded) {
                    try {
                        nativeRelease()
                    } catch (t: Throwable) {
                        Log.w(TAG, "nativeRelease error: ${t.message}")
                    }
                }
            }
            OnDeviceEngineLock.releaseExclusive(this)
            Log.i(TAG, "llama.cpp engine released")
        }
    }

    /** Invalidate any ongoing generation. */
    fun cancel() {
        generationEpoch.incrementAndGet()
    }

    private fun resolveArchitecture(): ModelArchitecture {
        if (modelArchitecture != ModelArchitecture.AUTO) return modelArchitecture
        val lower = modelPath.lowercase()
        return when {
            lower.contains("gemma") -> ModelArchitecture.GEMMA_4
            lower.contains("qwen") -> ModelArchitecture.QWEN_3_5
            else -> ModelArchitecture.GEMMA_4
        }
    }

    /**
     * Formats prompt according to canonical candidate chat templates.
     */
    internal fun formatPromptForArchitecture(prompt: String, arch: ModelArchitecture): String = when (arch) {
        ModelArchitecture.GEMMA_4 -> {
            // Gemma 4 chat template: <start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        }
        ModelArchitecture.QWEN_3_5 -> {
            // Qwen 3.5 chat template: <|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
            "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
        }
        ModelArchitecture.AUTO -> prompt
    }

    /**
     * Strips special end-of-turn tokens from generated completion.
     */
    internal fun cleanModelOutput(raw: String?, arch: ModelArchitecture): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()
        when (arch) {
            ModelArchitecture.GEMMA_4 -> {
                text = text.removeSuffix("<end_of_turn>")
                    .removeSuffix("<eos>")
                    .trim()
            }
            ModelArchitecture.QWEN_3_5 -> {
                text = text.removeSuffix("<|im_end|>")
                    .removeSuffix("<|endoftext|>")
                    .trim()
            }
            else -> {}
        }
        return text.ifBlank { null }
    }

    // Real native declarations backed by libllama.so
    private external fun nativeLoadModel(path: String, threads: Int, contextLength: Int): Boolean
    private external fun nativeGenerate(prompt: String): String?
    private external fun nativeRelease()

    companion object {
        const val TAG = "AniobLlamaCpp"
        const val LLAMA_LIBRARY = "llama"
        const val PINNED_COMMIT = "b3950"
        const val BUILD_CONFIG = "arm64-v8a+kleidiai+dotprod"
    }
}
