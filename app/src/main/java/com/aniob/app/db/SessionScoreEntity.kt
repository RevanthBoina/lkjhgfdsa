package com.aniob.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_scores")
data class SessionScoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: String,
    val userPrompt: String,
    val status: String, // SUCCESS, FAILED, ABORTED
    val totalSteps: Int,
    val durationMs: Long,
    val tokensUsed: Int,
    val providerUsed: String, // INTENT, FASTPATH, LOCAL_SLM, OMNIROUTE_CLOUD
    val decisionReason: String,
    val timestamp: Long = System.currentTimeMillis()
)
