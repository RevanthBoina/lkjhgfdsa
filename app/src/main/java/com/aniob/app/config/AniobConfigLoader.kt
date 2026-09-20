package com.aniob.app.config

import android.content.Context
import com.aniob.core.config.AgentLimits
import com.aniob.core.config.AniobConfigParser
import com.aniob.core.config.AniobDecodeConfig

/**
 * Loads bundled `configs/` from APK assets so the running APK's prompts and decode parameters
 * are the same files the repository ships (finding #4: repo config that never reached the APK
 * was dead on arrival).
 *
 * Fallback order is assets -> compiled defaults. A missing or corrupt asset must never block
 * task start; it degrades to [AniobDecodeConfig.defaults] / [AgentLimits.DEFAULT] instead.
 * `scripts/config-assets-sync-check.sh` guards the assets against drifting from `configs/`.
 */
class AniobConfigLoader(private val context: Context) {

    private val cache = mutableMapOf<String, String?>()

    fun decodeConfigs(): Map<AniobDecodeConfig.Role, AniobDecodeConfig> {
        val yaml = readAsset("aniob_config/models.yaml") ?: return AniobDecodeConfig.defaults
        return try {
            AniobDecodeConfig.fromEntries(AniobConfigParser.parseGeneration(yaml))
        } catch (_: Exception) {
            AniobDecodeConfig.defaults
        }
    }

    fun agentLimits(): AgentLimits {
        val yaml = readAsset("aniob_config/agent.yaml") ?: return AgentLimits.DEFAULT
        return try {
            AniobConfigParser.parseAgentLimits(yaml)
        } catch (_: Exception) {
            AgentLimits.DEFAULT
        }
    }

    /** Returns the bundled prompt text, or null so the caller can use a compiled fallback. */
    fun prompt(name: String): String? = readAsset("aniob_config/prompts/$name")

    private fun readAsset(path: String): String? = cache.getOrPut(path) {
        try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
