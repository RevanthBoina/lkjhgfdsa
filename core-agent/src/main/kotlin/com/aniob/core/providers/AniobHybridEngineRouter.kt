package com.aniob.core.providers

class AniobHybridEngineRouter(
    private val modelsDir: java.io.File,
    private val liteRtProvider: AniobLiteRtProvider = AniobLiteRtProvider(),
    private val embeddingProvider: com.aniob.core.embedding.AniobEmbeddingProvider? = null
) {
    fun getProviderForModel(modelId: String): AniobLocalLlmProvider {
        val path = java.io.File(modelsDir, "$modelId.gguf").absolutePath
        return when {
            modelId.contains("phi4") || modelId.contains("gemma3-1b") || modelId.contains("gemma3-4b") -> liteRtProvider // LiteRT fastest GPU delegate for Gemma/Phi on Pixel/Tensor
            modelId.contains("llama") || modelId.contains("qwen") || modelId.contains("mistral") -> AniobLlamaCppEngine(path) // llama.cpp best tool calling CPU KleidiAI
            else -> liteRtProvider
        }
    }

    fun canRunSecondEngine(totalRamGb: Int): Boolean = totalRamGb >= 8 // memory gate
}
