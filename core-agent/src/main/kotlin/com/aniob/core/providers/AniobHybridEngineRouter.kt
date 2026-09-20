package com.aniob.core.providers

class AniobHybridEngineRouter(
    private val modelsDir: java.io.File,
    private val embeddingProvider: com.aniob.core.embedding.AniobEmbeddingProvider? = null
) {
    fun getEngineForModel(modelId: String): AniobNativeLlmEngine {
        val path = modelPath(modelId)
        return if (usesLlamaCpp(modelId)) AniobLlamaCppEngine(path) else AniobLiteRtEngine(path)
    }

    fun getProviderForModel(modelId: String): AniobLocalLlmProvider =
        AniobLiteRtProvider(modelPath = modelPath(modelId))

    fun modelPath(modelId: String): String = java.io.File(modelsDir, "$modelId.gguf").absolutePath

    /**
     * LiteRT is the fastest GPU-delegate path for Gemma/Phi; llama.cpp wins for GGUF
     * tool-calling families (qwen/llama/mistral) thanks to KleidiAI CPU kernels.
     */
    fun usesLlamaCpp(modelId: String): Boolean =
        modelId.contains("llama") || modelId.contains("qwen") || modelId.contains("mistral")

    fun canRunSecondEngine(totalRamGb: Int): Boolean = totalRamGb >= 8 // memory gate
}
