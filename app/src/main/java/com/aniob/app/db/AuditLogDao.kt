package com.aniob.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 1")
    suspend fun getLastEntry(): AuditLogEntity?

    @Query("SELECT * FROM audit_logs ORDER BY id ASC")
    suspend fun getAll(): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun getByTask(taskId: String): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_logs")
    suspend fun count(): Long
}
