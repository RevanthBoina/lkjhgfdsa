package com.aniob.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.aniob.app.db.AniobDatabase
import com.aniob.app.metrics.EventLogger
import com.aniob.app.metrics.MetricsCollector
import com.aniob.app.system.AniobSystemState

class AniobApplication : Application(), ComponentCallbacks2 {

    lateinit var database: AniobDatabase
        private set
    lateinit var eventLogger: EventLogger
        private set
    lateinit var metricsCollector: MetricsCollector
        private set

    /** The single reactive source of permission/service truth (UX-0 §1). */
    lateinit var systemState: AniobSystemState
        private set

    val workflowRepository: com.aniob.app.workflow.WorkflowRepository by lazy {
        com.aniob.app.workflow.WorkflowRepository(database)
    }
    val taskOwner: com.aniob.app.workflow.TaskOwner by lazy {
        com.aniob.app.workflow.TaskOwner()
    }
    val approvalController: com.aniob.app.workflow.ApprovalController by lazy {
        com.aniob.app.workflow.ApprovalController()
    }
    val destinationPreferences: com.aniob.app.workflow.DestinationPreferences by lazy {
        com.aniob.app.workflow.DestinationPreferences(this)
    }
    val workflowPayloadStore: com.aniob.app.workflow.WorkflowPayloadStore by lazy {
        com.aniob.app.workflow.WorkflowPayloadStore(this)
    }
    val platformTools: com.aniob.app.workflow.PlatformTools by lazy {
        com.aniob.app.workflow.PlatformTools(this)
    }
    val workflowActionHost: com.aniob.app.workflow.WorkflowActionHost by lazy {
        com.aniob.app.workflow.WorkflowActionHost()
    }
    val incomingContentReader: com.aniob.app.workflow.IncomingContentReader by lazy {
        com.aniob.app.workflow.IncomingContentReader(this)
    }
    val workflowCoordinator: com.aniob.app.workflow.WorkflowCoordinator by lazy {
        com.aniob.app.workflow.WorkflowCoordinator(
            repository = workflowRepository,
            taskOwner = taskOwner,
            approvalController = approvalController,
            platformTools = platformTools,
            payloadStore = workflowPayloadStore,
            actionHost = workflowActionHost,
            destinationPreferences = destinationPreferences
        )
    }

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
        systemState = AniobSystemState(this).also { it.start() }

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
