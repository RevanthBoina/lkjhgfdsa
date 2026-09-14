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
    val timestamp: Long = System.currentTimeMillis(),
    val lastRoutingReason: String = "",
    val screenReads: Int = 0,
    val actions: Int = 0,
    val escalations: Int = 0,
    val memoryRetrievals: Int = 0,
    val embeddingSearches: Int = 0
)
