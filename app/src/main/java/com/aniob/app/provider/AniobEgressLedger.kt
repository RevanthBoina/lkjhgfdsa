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
    private const val MAX_LEDGER_EVENTS = 500
    private val ringBuffer = ArrayDeque<EgressEvent>(MAX_LEDGER_EVENTS + 1)
    private var runningPromptChars: Long = 0L
    private var runningCompletionChars: Long = 0L
    private val _events = MutableStateFlow<List<EgressEvent>>(emptyList())
    val events: StateFlow<List<EgressEvent>> = _events.asStateFlow()

    @Synchronized
    fun record(provider: String, model: String, promptChars: Int, completionChars: Int, latencyMs: Long) {
        val event = EgressEvent(
            provider = provider,
            model = model,
            promptChars = promptChars,
            completionChars = completionChars,
            latencyMs = latencyMs
        )
        if (ringBuffer.size >= MAX_LEDGER_EVENTS && ringBuffer.isNotEmpty()) {
            val evicted = ringBuffer.removeFirst()
            runningPromptChars = (runningPromptChars - evicted.promptChars).coerceAtLeast(0L)
            runningCompletionChars = (runningCompletionChars - evicted.completionChars).coerceAtLeast(0L)
        }
        ringBuffer.addLast(event)
        runningPromptChars += promptChars
        runningCompletionChars += completionChars
        _events.value = ringBuffer.toList()
    }

    @Synchronized
    fun getTotalEgressCount(): Int = ringBuffer.size

    @Synchronized
    fun getTotalPromptChars(): Int = runningPromptChars.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    @Synchronized
    fun getTotalCompletionChars(): Int = runningCompletionChars.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    @Synchronized
    fun clear() {
        ringBuffer.clear()
        runningPromptChars = 0L
        runningCompletionChars = 0L
        _events.value = emptyList()
    }
}
