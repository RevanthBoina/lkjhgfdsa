package com.aniob.app.metrics

import android.util.Log
import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.LogEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EventLogger(private val database: AniobDatabase) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

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
