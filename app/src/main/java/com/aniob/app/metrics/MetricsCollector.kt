package com.aniob.app.metrics

import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.SessionScoreEntity
import kotlinx.coroutines.flow.Flow

data class AggregateMetrics(
    val totalTasks: Int = 0,
    val successRatePct: Float = 0.0f,
    val totalTokensSavedFastPath: Int = 0,
    val avgLatencyMs: Long = 0L,
    val cloudCallsCount: Int = 0,
    val localSlmCallsCount: Int = 0,
    val fastPathHitsCount: Int = 0,
    val intentHitsCount: Int = 0
)

class MetricsCollector(private val database: AniobDatabase) {

    fun getSessionScores(): Flow<List<SessionScoreEntity>> {
        return database.sessionScoreDao().getAllScores()
    }

    suspend fun recordSession(
        taskId: String,
        prompt: String,
        isSuccess: Boolean,
        steps: Int,
        durationMs: Long,
        tokensUsed: Int,
        providerUsed: String,
        decisionReason: String
    ): Long {
        val entity = SessionScoreEntity(
            taskId = taskId,
            userPrompt = prompt,
            status = if (isSuccess) "SUCCESS" else "FAILED",
            totalSteps = steps,
            durationMs = durationMs,
            tokensUsed = tokensUsed,
            providerUsed = providerUsed,
            decisionReason = decisionReason
        )
        return database.sessionScoreDao().insertScore(entity)
    }

    suspend fun recordWorkflowOutcome(
        taskId: String,
        prompt: String,
        result: com.aniob.core.workflow.WorkflowResult,
        durationMs: Long = 0L
    ): Long {
        val (status, decisionReason) = when (result) {
            is com.aniob.core.workflow.WorkflowResult.CompletedWithEvidence -> "SUCCESS" to "Completed: ${result.evidenceKind}"
            is com.aniob.core.workflow.WorkflowResult.UserReportedCompletion -> "SUCCESS" to "User marked complete"
            is com.aniob.core.workflow.WorkflowResult.Failed -> "FAILED" to result.errorReason
            is com.aniob.core.workflow.WorkflowResult.Cancelled -> "CANCELLED" to result.reason
            is com.aniob.core.workflow.WorkflowResult.LaunchAccepted -> "WAITING" to "Launch accepted"
            is com.aniob.core.workflow.WorkflowResult.WaitingForUser -> "WAITING" to result.reason
            is com.aniob.core.workflow.WorkflowResult.EffectUnknown -> "UNKNOWN" to result.details
        }

        if (status == "WAITING" || status == "UNKNOWN") {
            return -1L
        }

        val entity = SessionScoreEntity(
            taskId = taskId,
            userPrompt = prompt,
            status = status,
            totalSteps = 1,
            durationMs = durationMs,
            tokensUsed = 0,
            providerUsed = "NATIVE_WORKFLOW",
            decisionReason = decisionReason
        )
        return database.sessionScoreDao().insertScore(entity)
    }
}
