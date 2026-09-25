package com.aniob.app.metrics

import android.util.Log
import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.LogEventEntity
import com.aniob.core.domain.AniobAction
import com.aniob.core.logging.AniobXLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Append-only log/audit wrapper plus a BrowserUse-style structured trajectory stream.
 * PRIVACY: trajectory [TrajectoryStep] fields carry categorical tokens / action summaries
 * only — never the raw user utterance (mirrors AniobXLog's mandate).
 */
class EventLogger(private val database: AniobDatabase) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /** BrowserUse-style append-only trajectory for the current task (in-memory, privacy-safe). */
    data class TrajectoryStep(
        val stepIndex: Int,
        val observation: String,
        val thought: String,
        val action: AniobAction,
        val latencyMs: Long,
        val screenHash: String,
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val textEvidence: String? = null,
        val targetBounds: com.aniob.core.domain.AniobRect? = null
    )

    companion object {
        const val MAX_TRAJECTORY_CAPACITY = 50
    }

    private val trajectory = mutableListOf<TrajectoryStep>()

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    @Synchronized
    fun logStep(step: TrajectoryStep) {
        if (trajectory.size >= MAX_TRAJECTORY_CAPACITY) {
            trajectory.removeAt(0)
        }
        trajectory.add(step)
        // Privacy: never store raw user text. Sensitive gates are referenced only via
        // categorical audit entries (stepIndex + targetPackage), not the raw message.
        if (step.action is AniobAction.ConfirmWithUser) {
            AniobXLog.recordEvent(AniobXLog.SecurityEvent.CONFIRMATION_GATE_TRIGGERED, stepIndex = step.stepIndex)
        }
    }

    @Synchronized
    fun snapshotTrajectory(): List<TrajectoryStep> = ArrayList(trajectory)

    @Synchronized
    fun clearTrajectory() {
        trajectory.clear()
    }

    /**
     * Appends a terminal session-counter summary through the existing persistence path so it
     * lands in `event_logs` (categorical counters only — no user text).
     */
    @Synchronized
    fun recordSessionCounters(
        screenReads: Int,
        actions: Int,
        escalations: Int,
        elapsedMs: Long,
        stateTrace: List<String>,
        providerDecisionReason: String,
        lastRoutingReason: String
    ) {
        info(
            "AniobMetrics",
            "SESSION_COUNTERS{" +
                "screenReads=$screenReads," +
                "actions=$actions," +
                "escalations=$escalations," +
                "elapsedMs=$elapsedMs," +
                "stateTrace=${stateTrace.takeLast(12).joinToString("->")}," +
                "decisionReason=$providerDecisionReason," +
                "lastRoutingReason=$lastRoutingReason" +
                "}"
        )
    }

    private fun log(level: String, tag: String, message: String) {
        when (level) {
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }
        scope.launch {
            try {
                database.logEventDao().insertLog(
                    LogEventEntity(
                        level = level,
                        tag = tag,
                        message = message
                    )
                )
            } catch (e: Exception) {
                Log.e("EventLogger", "Failed to persist log: ${e.message}")
            }
        }
    }
}
