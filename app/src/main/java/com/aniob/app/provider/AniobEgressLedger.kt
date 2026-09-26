package com.aniob.app.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EgressEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val provider: String,
    val model: String,
    val promptChars: Int,
    val completionChars: Int,
    val latencyMs: Long
)

object AniobEgressLedger {
    private val _events = MutableStateFlow<List<EgressEvent>>(emptyList())
    val events: StateFlow<List<EgressEvent>> = _events.asStateFlow()

    fun record(provider: String, model: String, promptChars: Int, completionChars: Int, latencyMs: Long) {
        val event = EgressEvent(
            provider = provider,
            model = model,
            promptChars = promptChars,
            completionChars = completionChars,
            latencyMs = latencyMs
        )
        _events.value = _events.value + event
    }

    fun getTotalEgressCount(): Int = _events.value.size
    fun getTotalPromptChars(): Int = _events.value.sumOf { it.promptChars }
    fun getTotalCompletionChars(): Int = _events.value.sumOf { it.completionChars }
    fun clear() {
        _events.value = emptyList()
    }
}
