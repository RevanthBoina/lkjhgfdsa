package com.aniob.app.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workflow_records")
data class WorkflowEntity(
    @PrimaryKey
    val taskId: String,
    val actionId: String = "",
    val generation: Long = 0L,
    val state: String = "Draft",
    val destinationKey: String = "",
    val destinationUrl: String? = null,
    val payloadRefId: String? = null,
    val payloadHash: String? = null,
    val resultKind: String? = null,
    val evidence: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = 1
)

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflow_records WHERE taskId = :taskId LIMIT 1")
    suspend fun getWorkflow(taskId: String): WorkflowEntity?

    @Query("SELECT * FROM workflow_records ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestWorkflow(): WorkflowEntity?

    @Query("SELECT * FROM workflow_records WHERE state = 'WaitingForUser' ORDER BY updatedAt DESC")
    fun getWaitingWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflow_records WHERE state = 'WaitingForUser' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestWaitingWorkflow(): WorkflowEntity?

    @Query("SELECT * FROM workflow_records WHERE state = 'WaitingForUser' ORDER BY updatedAt DESC")
    suspend fun getAllWaitingWorkflows(): List<WorkflowEntity>

    @Query("SELECT * FROM workflow_records WHERE state = 'Dispatching' ORDER BY updatedAt DESC")
    suspend fun getAllDispatchingWorkflows(): List<WorkflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: WorkflowEntity)

    @Query("DELETE FROM workflow_records WHERE taskId = :taskId")
    suspend fun deleteWorkflow(taskId: String)

    @Query("SELECT * FROM workflow_records ORDER BY updatedAt DESC")
    fun getAllWorkflows(): Flow<List<WorkflowEntity>>
}

object WorkflowDatabaseMigrations {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS active_tasks (
                    taskId TEXT NOT NULL PRIMARY KEY,
                    rawPrompt TEXT NOT NULL,
                    currentStep INTEGER NOT NULL,
                    lastKnownEffect TEXT NOT NULL DEFAULT '',
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS workflow_records (
                    taskId TEXT NOT NULL PRIMARY KEY,
                    actionId TEXT NOT NULL DEFAULT '',
                    generation INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'Draft',
                    destinationKey TEXT NOT NULL DEFAULT '',
                    destinationUrl TEXT,
                    payloadRefId TEXT,
                    payloadHash TEXT,
                    resultKind TEXT,
                    evidence TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    schemaVersion INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
        }
    }
}
