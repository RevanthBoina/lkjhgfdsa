package com.aniob.app.model

import android.util.Log
import com.aniob.core.providers.AniobNativeLlmEngine

/**
 * Process-wide single loaded heavyweight engine coordinator.
 *
 * Aniob Invariant (AGENTS.md / Plan 0.3):
 * Phones with 8GB RAM have ~3.5GB available user process memory. Two heavyweight engines
 * (>1.5GB each) must NEVER coexist in memory simultaneously, or the Android Low Memory
 * Killer (LMK) will terminate the app.
 *
 * This singleton enforces that loading any engine (LiteRT-LM or experimental llama.cpp)
 * explicitly releases any currently loaded engine before allocating native buffers.
 */
object OnDeviceEngineLock {

    private const val TAG = "OnDeviceEngineLock"
    private val lock = Any()

    @Volatile
    private var activeEngine: AniobNativeLlmEngine? = null

    @Volatile
    private var activeEngineName: String = "NONE"

    /**
     * Request exclusive activation of an engine.
     * If another engine is currently active, releases it first.
     */
    fun claimExclusive(engine: AniobNativeLlmEngine, name: String) = synchronized(lock) {
        val current = activeEngine
        if (current != null && current !== engine) {
            Log.i(TAG, "Releasing existing engine '$activeEngineName' to activate '$name'")
            try {
                current.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error releasing active engine '$activeEngineName': ${e.message}")
            }
        }
        activeEngine = engine
        activeEngineName = name
    }

    /**
     * Release the active engine if it matches [engine].
     */
    fun releaseExclusive(engine: AniobNativeLlmEngine) = synchronized(lock) {
        if (activeEngine === engine) {
            Log.i(TAG, "Releasing engine '$activeEngineName'")
            try {
                engine.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error releasing engine '$activeEngineName': ${e.message}")
            } finally {
                activeEngine = null
                activeEngineName = "NONE"
            }
        }
    }

    fun getActiveEngineName(): String = activeEngineName
}
