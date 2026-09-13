package com.aniob.core.domain

/**
 * Metric snapshot logged for every execution step and aggregated into SessionScore.
 */
data class AniobMetricSnapshot(
    val taskId: String,
    val stepIndex: Int,
    val provider: String,
    val providerDecisionReason: String,
    val stepLatencyMs: Long,
    val verificationLatencyMs: Long,
    val tokensUsed: Int,
    val fastPathHit: Boolean,
    val waterfallLevelUsed: Int,
    val timestamp: Long = System.currentTimeMillis()
)
