package com.aniob.app.ui.chat

import androidx.compose.runtime.Immutable
import com.aniob.core.domain.AniobAction

/**
 * Immutable UI models for chat screen rendering.
 * Stable keys guarantee 60fps recomposition performance.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val stepIndex: Int? = null,
    val action: AniobAction? = null,
    val isStreaming: Boolean = false,
    val badge: String? = null,
    val provider: String? = null
)

@Immutable
data class ExecutionTickerState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val statusText: String = "Ready",
    val provider: String = "NONE",
    val activeTaskPrompt: String = ""
)
