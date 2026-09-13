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
        LogEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AniobDatabase : RoomDatabase() {
    abstract fun sessionScoreDao(): SessionScoreDao
    abstract fun appKnowledgeBaseDao(): AppKnowledgeBaseDao
    abstract fun tipDao(): TipDao
    abstract fun logEventDao(): LogEventDao

    companion object {
        @Volatile
        private var INSTANCE: AniobDatabase? = null

        fun getDatabase(context: Context): AniobDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AniobDatabase::class.java,
                    "aniob_database.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
