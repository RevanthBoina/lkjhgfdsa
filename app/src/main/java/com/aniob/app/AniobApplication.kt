package com.aniob.app

import android.app.Application
import com.aniob.app.db.AniobDatabase
import com.aniob.app.metrics.EventLogger
import com.aniob.app.metrics.MetricsCollector

class AniobApplication : Application() {

    lateinit var database: AniobDatabase
        private set
    lateinit var eventLogger: EventLogger
        private set
    lateinit var metricsCollector: MetricsCollector
        private set

    companion object {
        lateinit var instance: AniobApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AniobDatabase.getDatabase(this)
        eventLogger = EventLogger(database)
        metricsCollector = MetricsCollector(database)

        eventLogger.info("AniobApplication", "Aniob Agent Engine initialized successfully.")
    }
}
