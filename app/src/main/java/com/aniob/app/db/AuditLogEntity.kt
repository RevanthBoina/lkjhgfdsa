package com.aniob.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only, tamper-evident audit log entity for all autonomous device actions.
 * Chained via cryptographic hashes (previousHash -> entryHash).
 */
@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val taskId: String,
    val stepIndex: Int,
    val actionType: String,
    val targetPackage: String,
    val sanitizedPayload: String,
    val verificationStatus: String,
    val previousHash: String,
    val entryHash: String
)
