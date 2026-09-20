package com.aniob.core.providers

/**
 * Real llama.cpp engine. Chosen over LiteRT for GGUF tool-calling models (qwen/llama/mistral)
 * where KleidiAI CPU kernels are the fastest option.
 */
class AniobLlamaCppEngine(private val modelPath: String) : AniobNativeLlmEngine {

    private var nativeLoaded: Boolean = try {
        System.loadLibrary(LLAMA_LIBRARY)
        true
    } catch (_: Throwable) {
        false
    }

    override fun isReady(): Boolean = nativeLoaded && java.io.File(modelPath).exists()

    override fun load(path: String): Boolean {
        if (!isReady()) return false
        return try {
            nativeLoadModel(path)
        } catch (_: Throwable) {
            false
        }
    }

    override fun generate(prompt: String): String? = try {
        nativeGenerate(prompt)
    } catch (_: Throwable) {
        null
    }

    override fun release() {
        try {
            nativeRelease()
        } catch (_: Throwable) {
            // Native layer already unloaded; nothing to release.
        }
        nativeLoaded = false
    }

    // Native methods - implemented in C++ via NDK arm64-v8a only.
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String): String
    private external fun nativeRelease()

    companion object {
        const val LLAMA_LIBRARY = "llama"
    }
}