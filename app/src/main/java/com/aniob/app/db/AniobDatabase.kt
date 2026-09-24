package com.aniob.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionScoreEntity::class,
        AppKnowledgeBaseEntity::class,
        TipEntity::class,
        LogEventEntity::class,
        ActiveTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AniobDatabase : RoomDatabase() {
    abstract fun sessionScoreDao(): SessionScoreDao
    abstract fun appKnowledgeBaseDao(): AppKnowledgeBaseDao
    abstract fun tipDao(): TipDao
    abstract fun logEventDao(): LogEventDao
    abstract fun activeTaskDao(): ActiveTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AniobDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN kind TEXT NOT NULL DEFAULT 'FASTPATH'")
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN artifactJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_knowledge_base ADD COLUMN reviewState TEXT NOT NULL DEFAULT 'VERIFIED'")
            }
        }

        fun getDatabase(context: Context): AniobDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AniobDatabase::class.java,
                    "aniob_database.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
