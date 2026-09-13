package com.aniob.app.ui.chat

import com.aniob.core.providers.AniobLocalLlmClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller for streaming tokens into the chat typing bubble.
 * Enforces 60ms UI update throttling (matching cloud streaming path).
 * Provides onTrimMemory() hook to release native LiteRT / SLM memory buffers.
 */
class AniobStreamingController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    private val _streamingBubbleText = MutableStateFlow("")
    val streamingBubbleText: StateFlow<String> = _streamingBubbleText.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private var buffer = StringBuilder()
    private var throttleJob: Job? = null
    private var isThrottling = false

    /**
     * Called by provider delta callbacks. Emits to the UI at a 60ms throttle.
     */
    fun appendTokenDelta(delta: String) {
        buffer.append(delta)
        if (!isThrottling) {
            isThrottling = true
            throttleJob = scope.launch {
                delay(60) // 60ms throttle
                _streamingBubbleText.value = buffer.toString()
                isThrottling = false
            }
        }
    }

    fun startStreaming() {
        buffer.clear()
        _streamingBubbleText.value = ""
        _isStreaming.value = true
    }

    fun finishStreaming(): String {
        throttleJob?.cancel()
        val finalResult = buffer.toString()
        _streamingBubbleText.value = finalResult
        _isStreaming.value = false
        isThrottling = false
        return finalResult
    }

    /**
     * Memory pressure hook: Releases native LiteRT SLM engine to prevent OOM kills.
     */
    fun onTrimMemory(level: Int) {
        // Under moderate or critical memory pressure, release local LLM native engine
        AniobLocalLlmClient.release()
    }

    fun cleanup() {
        throttleJob?.cancel()
        scope.cancel()
    }
}
