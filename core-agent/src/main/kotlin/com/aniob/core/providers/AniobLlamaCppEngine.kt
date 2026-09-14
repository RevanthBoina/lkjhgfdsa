package com.aniob.core.providers

class AniobLlamaCppEngine(private val modelPath: String) : AniobLocalLlmProvider {
    private var isLoaded = false
    init {
        try {
            System.loadLibrary("llama")
            isLoaded = true
        } catch (_: Throwable) {
            isLoaded = false
        }
    }

    // Native methods - implemented in C++ via NDK arm64-v8a only
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String): String
    private external fun nativeRelease()

    override fun isAvailable(): Boolean = isLoaded && java.io.File(modelPath).exists()

    override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String {
        if (!isAvailable()) throw IllegalStateException("llama.cpp model not available $modelPath")
        if (!nativeLoadModel(modelPath)) throw IllegalStateException("Failed to load GGUF")
        val full = nativeGenerate(prompt)
        // Simulate streaming delta 60ms throttle like LiteRT path
        val tokens = full.chunked(4)
        val sb = StringBuilder()
        for (t in tokens) {
            try { Thread.sleep(15) } catch (_: InterruptedException) {}
            sb.append(t)
            onDelta(t)
        }
        return sb.toString()
    }

    override fun release() {
        try {
            nativeRelease()
        } catch (_: Throwable) {}
        isLoaded = false
    }
}
