package com.aniob.core.providers

/**
 * Common abstraction for On-Device SLM (Small Language Model) inference.
 */
interface AniobLocalLlmEngine {
    fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String
    fun generateStep(systemPrompt: String, userPrompt: String): String

    /**
     * Structured generation outcome. Implementations that can distinguish "no model" from
     * "generated an action" must override this so the agent loop never executes fabricated steps.
     * Default keeps compatibility for simple non-agent engines (answers, not actions).
     */
    fun generateStepResult(systemPrompt: String, userPrompt: String): AniobGenerationResult {
        val text = generateStep(systemPrompt, userPrompt)
        return if (text.isBlank()) AniobGenerationResult.GenerationFailed("Engine produced no output")
        else AniobGenerationResult.Ready(text)
    }

    fun isAvailable(): Boolean
    fun isModelLoaded(): Boolean = isAvailable()
    fun release()
}

/**
 * Factory and singleton registry for on-device SLM inference.
 */
object AniobLocalLlmClient {
    private var defaultInstance: AniobLocalLlmEngine? = null

    @Synchronized
    fun getEngine(): AniobLocalLlmEngine {
        if (defaultInstance == null) {
            defaultInstance = AniobLocalLlmEngineImpl(AniobLiteRtProvider())
        }
        return defaultInstance!!
    }

    /**
     * Registers an engine bound to a specific on-device model file (e.g. a downloaded GGUF),
     * replacing the default path-based singleton. Returns the active engine.
     */
    @Synchronized
    fun useEngineForModel(modelPath: String): AniobLocalLlmEngine {
        defaultInstance?.release()
        defaultInstance = AniobLocalLlmEngineImpl(AniobLiteRtProvider(modelPath = modelPath))
        return defaultInstance!!
    }

    @Synchronized
    fun getInstance(): AniobLocalLlmEngine = getEngine()

    @Synchronized
    fun release() {
        defaultInstance?.release()
        defaultInstance = null
    }
}

class AniobLocalLlmEngineImpl(
    private val provider: AniobLiteRtProvider
) : AniobLocalLlmEngine {

    override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String {
        return provider.chatStreaming(prompt, onDelta)
    }

    override fun generateStep(systemPrompt: String, userPrompt: String): String {
        return provider.chatStreaming("$systemPrompt\n$userPrompt") { /* stream delta */ }
    }

    override fun generateStepResult(systemPrompt: String, userPrompt: String): AniobGenerationResult =
        provider.chatStreamingResult("$systemPrompt\n$userPrompt") { /* stream delta */ }

    override fun isAvailable(): Boolean = provider.isAvailable()

    override fun release() {
        provider.release()
    }
}
