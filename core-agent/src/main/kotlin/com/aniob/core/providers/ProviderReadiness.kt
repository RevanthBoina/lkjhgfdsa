package com.aniob.core.providers

/**
 * Five-tier readiness model for on-device SLM execution.
 * Automation is ready ONLY when readiness reaches [ACTION_CAPABLE].
 */
enum class ProviderReadiness {
    FILE_MISSING,
    MODEL_FILE_PRESENT,
    ENGINE_LOADABLE,
    ENGINE_LOADED,
    ACTION_CAPABLE
}
