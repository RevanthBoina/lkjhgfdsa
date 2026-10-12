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

    fun modelPath(modelId: String): String = AniobModelFiles.absolutePath(modelsDir, modelId)

    /**
     * Every catalog model is now a `.litertlm` file loaded through the LiteRT-LM runtime, so the
     * llama.cpp branch is unreachable: RUNTIME_DECISION.md rejected Option B (building llama.cpp
     * from source under the NDK) and this project neither builds nor ships a `libllama.so`.
     * Kept as a single decision point rather than deleted, so that re-introducing a second engine
     * later is one edit here instead of a search across call sites.
     */
    fun usesLlamaCpp(modelId: String): Boolean = false

    fun canRunSecondEngine(totalRamGb: Int): Boolean = totalRamGb >= 8 // memory gate
}
