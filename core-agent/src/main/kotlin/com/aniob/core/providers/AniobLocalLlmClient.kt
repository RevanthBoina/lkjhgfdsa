package com.aniob.core.providers

/**
 * Common abstraction for On-Device SLM (Small Language Model) inference.
 */
interface AniobLocalLlmEngine {
    fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String
    fun generateStep(systemPrompt: String, userPrompt: String): String
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

    @Synchronized
    fun getInstance(): AniobLocalLlmEngine = getEngine()

    @Synchronized
    fun release() {
        defaultInstance?.release()
        defaultInstance = null
    }
}

class AniobLocalLlmEngineImpl(
    private val provider: AniobLocalLlmProvider
) : AniobLocalLlmEngine {

    override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String {
        return provider.chatStreaming(prompt, onDelta)
    }

    override fun generateStep(systemPrompt: String, userPrompt: String): String {
        return provider.chatStreaming("$systemPrompt\n$userPrompt") { /* stream delta */ }
    }

    override fun isAvailable(): Boolean = provider.isAvailable()

    override fun release() {
        provider.release()
    }
}
