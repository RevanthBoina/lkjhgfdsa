package com.aniob.core.providers

/**
 * Common abstraction for On-Device SLM (Small Language Model) inference.
 */
interface AniobLocalLlmEngine {
    fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String
    fun generateStep(systemPrompt: String, userPrompt: String): String

    /**
     * What this engine can actually be trusted to do. Used to decide routing: an engine that
     * cannot yet emit schema-valid *actions* on the target device must not be reported as
     * LOCAL_SLM in metrics (finding #6) — route to cloud/mock with a logged reason instead.
     */
    fun capabilities(): AniobEngineCapabilities = AniobEngineCapabilities()

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
 * Honest capability report for an on-device engine.
 *
 * [canDriveActions] defaults to false until an engine is *proven* on a real device to emit
 * schema-valid actions. This is the quarantine switch: a model that only answers questions must
 * never be allowed to move the UI.
 */
data class AniobEngineCapabilities(
    val canAnswer: Boolean = true,
    val canDriveActions: Boolean = false,
    val reason: String = "Action driving unverified on this engine"
)

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

    override fun capabilities(): AniobEngineCapabilities = provider.capabilities()

    override fun release() {
        provider.release()
    }
}
