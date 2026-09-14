package com.aniob.core.exec

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
    val actionsTaken: Int = 0,
    val escalations: Int = 0,
    val elapsedMs: Long = 0,
    val stateTrace: List<String> = emptyList()
)
