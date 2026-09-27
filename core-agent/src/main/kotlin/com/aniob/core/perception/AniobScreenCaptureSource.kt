package com.aniob.core.perception

import com.aniob.core.domain.AniobScreenState

/**
 * Perception capture contract implemented by :app and consumed by :core-agent.
 * Completely isolates Android framework AccessibilityService from pure-JVM core.
 */
interface AniobScreenCaptureSource {
    /**
     * Captures the screen state across all visible windows, enforcing
     * multi-window traversal, bounds clipping, and sanitization.
     */
    fun captureScreen(includeInvisible: Boolean = false): AniobScreenState
}
