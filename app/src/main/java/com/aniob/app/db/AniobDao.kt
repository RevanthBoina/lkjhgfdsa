package com.aniob.app.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionScoreDao {
    @Query("SELECT * FROM session_scores ORDER BY timestamp DESC")
    fun getAllScores(): Flow<List<SessionScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScore(score: SessionScoreEntity): Long

    @Query("SELECT COUNT(*) FROM session_scores")
    suspend fun countScores(): Int

    @Query("SELECT * FROM session_scores WHERE status = :status ORDER BY timestamp DESC")
    fun getScoresByStatus(status: String): Flow<List<SessionScoreEntity>>
}

@Dao
interface AppKnowledgeBaseDao {
    @Query("SELECT * FROM app_knowledge_base WHERE taskSignature = :signature LIMIT 1")
    suspend fun findBySignature(signature: String): AppKnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMacro(entry: AppKnowledgeBaseEntity)

    @Query("SELECT * FROM app_knowledge_base ORDER BY lastUpdated DESC")
    fun getAllKnowledge(): Flow<List<AppKnowledgeBaseEntity>>
}

@Dao
interface TipDao {
    @Query("SELECT * FROM learned_tips WHERE packageName = :packageName ORDER BY confidence DESC")
    suspend fun getTipsForPackage(packageName: String): List<TipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTip(tip: TipEntity): Long
}

@Dao
interface LogEventDao {
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<LogEventEntity>>

    @Insert
    suspend fun insertLog(log: LogEventEntity): Long
}
