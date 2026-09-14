package com.aniob.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.aniob.app.db.AniobDatabase
import com.aniob.app.metrics.EventLogger
import com.aniob.app.metrics.MetricsCollector

class AniobApplication : Application(), ComponentCallbacks2 {

    lateinit var database: AniobDatabase
        private set
    lateinit var eventLogger: EventLogger
        private set
    lateinit var metricsCollector: MetricsCollector
        private set

    /**
     * Registered by AniobViewModel so the LiteRT engine can be released on
     * memory pressure without the Application holding an Android runtime dependency.
     */
    var onMemoryTrimListener: ((level: Int) -> Unit)? = null

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            eventLogger.info("AniobApplication", "onTrimMemory level=$level -> reflex releasing LiteRT engine")
            onMemoryTrimListener?.invoke(level)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() {
        eventLogger.info("AniobApplication", "onLowMemory -> reflex releasing LiteRT engine")
        onMemoryTrimListener?.invoke(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
