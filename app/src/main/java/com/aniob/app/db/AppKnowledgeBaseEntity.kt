package com.aniob.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_knowledge_base")
data class AppKnowledgeBaseEntity(
    @PrimaryKey
    val taskSignature: String,
    val packageName: String,
    val macroStepsJson: String,
    val successCount: Int = 1,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "learned_tips")
data class TipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val tipText: String,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "event_logs")
data class LogEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val level: String, // INFO, WARN, ERROR, DEBUG
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
