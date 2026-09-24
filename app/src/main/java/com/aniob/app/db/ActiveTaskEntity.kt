package com.aniob.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_tasks")
data class ActiveTaskEntity(
    @PrimaryKey
    val taskId: String,
    val rawPrompt: String,
    val currentStep: Int,
    val lastKnownEffect: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
