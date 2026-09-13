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
}
