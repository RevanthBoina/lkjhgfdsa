package com.aniob.core.exec

import com.aniob.core.domain.AniobMetricSnapshot

enum class ExecOutcome {
    SUCCESS,
    FAILED_TARGET_NOT_FOUND,
    FAILED_ACTION,
    FAILED_VERIFICATION,
    TIMEOUT,
    BUDGET_EXCEEDED
}

data class ExecReport(
    val outcome: ExecOutcome,
    val reason: String,
    val screenReads: Int = 0,
    val actions: Int = 0,
    val escalations: Int = 0,
    val elapsedMs: Long = 0,
    val stateTrace: List<String> = emptyList(),
    val providerUsed: String = "NONE",
    val decisionReason: String = ""
) {
    /**
     * Maps this terminal report into an [AniobMetricSnapshot] so the provider decision
     * and per-task counters flow into the shared metrics stream (BrowserUse RouteDecision pattern).
     */
    fun toMetricSnapshot(taskId: String, stepIndex: Int = 0, timestamp: Long = System.currentTimeMillis()): AniobMetricSnapshot {
        return AniobMetricSnapshot(
            taskId = taskId,
            stepIndex = stepIndex,
            provider = providerUsed,
            providerDecisionReason = decisionReason,
            stepLatencyMs = elapsedMs,
            verificationLatencyMs = 0L,
            tokensUsed = 0,
            fastPathHit = providerUsed == "FASTPATH" || providerUsed == "INTENT",
            waterfallLevelUsed = 0,
            timestamp = timestamp,
            lastRoutingReason = decisionReason,
            screenReads = screenReads,
            actions = actions,
            escalations = escalations
        )
    }
}
