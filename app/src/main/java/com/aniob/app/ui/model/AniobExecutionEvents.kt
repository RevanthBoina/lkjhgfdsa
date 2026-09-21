package com.aniob.app.ui.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal execution-event bus (UX-3 seam).
 *
 * The pipeline cutover has not landed, so events are published at the existing ViewModel record
 * sites. Every consumer touches only the frozen [StepEvent], so swapping this for the real
 * pipeline feed later is invisible to the UI.
 *
 * `// SHIM(UX-3): delete when the StepPipeline cutover emits StepEvent directly.`
 */
object AniobExecutionEvents {

    private val _events = MutableSharedFlow<StepEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<StepEvent> = _events.asSharedFlow()

    private val _lastEvent = MutableStateFlow<StepEvent?>(null)
    val lastEvent: StateFlow<StepEvent?> = _lastEvent.asStateFlow()

    /** Called from the single dispatch/record path; safe from any thread. */
    fun publish(event: StepEvent) {
        _lastEvent.value = event
        _events.tryEmit(event)
    }

    fun reset() {
        _lastEvent.value = null
    }
}
