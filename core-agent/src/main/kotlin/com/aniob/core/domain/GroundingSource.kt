package com.aniob.core.domain

/**
 * Indicates which perception source resolved a semantic target.
 * Surface telemetry tracking how often Aniob flies on structure vs pixels.
 */
enum class GroundingSource {
    ACCESSIBILITY,
    OCR,
    VISUAL_MODEL
}
