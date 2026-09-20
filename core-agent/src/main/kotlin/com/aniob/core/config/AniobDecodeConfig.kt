package com.aniob.core.config

/**
 * Per-role generation parameters for the on-device and cloud models.
 *
 * Each loop role has a different job, so a single global temperature is wrong: planning wants
 * determinism and room to think, execution wants terse deterministic tool calls, reflection
 * benefits from slightly more diversity, and exploration more still. `jsonMode` is on wherever
 * the role must emit an action or verdict object.
 *
 * Pure JVM — no Android or config-file dependency. [defaults] are compiled in so the APK runs
 * correctly even when `configs/` assets are missing or corrupt.
 */
data class AniobDecodeConfig(
    val role: Role,
    val temperature: Double,
    val maxTokens: Int,
    val jsonMode: Boolean
) {
    enum class Role {
        PLANNER, EXECUTOR, REFLECTOR, GRILL, EXPLORER;

        companion object {
            /** Maps a loose config-file key (`grill_me`, `explore`, ...) onto a role. */
            fun fromKey(key: String): Role? = when (key.trim().lowercase().replace('-', '_')) {
                "planner", "plan" -> PLANNER
                "executor", "execute", "actor" -> EXECUTOR
                "reflector", "reflect", "reflection" -> REFLECTOR
                "grill", "grill_me", "grillme" -> GRILL
                "explorer", "explore", "exploration" -> EXPLORER
                else -> null
            }
        }
    }

    companion object {
        fun forRole(role: Role): AniobDecodeConfig = defaults[role] ?: defaults.getValue(Role.EXECUTOR)

        val defaults: Map<Role, AniobDecodeConfig> = mapOf(
            Role.PLANNER to AniobDecodeConfig(Role.PLANNER, temperature = 0.0, maxTokens = 512, jsonMode = true),
            Role.EXECUTOR to AniobDecodeConfig(Role.EXECUTOR, temperature = 0.0, maxTokens = 128, jsonMode = true),
            Role.REFLECTOR to AniobDecodeConfig(Role.REFLECTOR, temperature = 0.2, maxTokens = 256, jsonMode = true),
            Role.GRILL to AniobDecodeConfig(Role.GRILL, temperature = 0.3, maxTokens = 512, jsonMode = true),
            Role.EXPLORER to AniobDecodeConfig(Role.EXPLORER, temperature = 0.4, maxTokens = 256, jsonMode = true)
        )

        /**
         * Builds a config table from parsed `generation:` entries, falling back to [defaults]
         * for any role absent from the file. Unknown keys are ignored.
         */
        fun fromEntries(entries: Map<String, RoleGenParams>): Map<Role, AniobDecodeConfig> {
            val result = defaults.toMutableMap()
            entries.forEach { (key, params) ->
                val role = Role.fromKey(key) ?: return@forEach
                val base = defaults.getValue(role)
                result[role] = base.copy(
                    temperature = params.temperature ?: base.temperature,
                    maxTokens = params.maxTokens ?: base.maxTokens,
                    jsonMode = params.jsonMode ?: base.jsonMode
                )
            }
            return result
        }
    }

    /** Raw generation parameters as read from YAML, before defaults are merged. */
    data class RoleGenParams(
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val jsonMode: Boolean? = null
    )
}

/**
 * Agent-level execution limits, loaded from `configs/agent.yaml`.
 * These replace hardcoded literals in the execution loop.
 */
data class AgentLimits(
    val maxStepsPerTask: Int = 25,
    val stepTimeoutMs: Long = 15_000L,
    val maxNodesPerScreen: Int = 60,
    val watchdogLoopThreshold: Int = 3,
    val settleTimeoutMs: Long = 1_500L,
    val minWaitMs: Long = 200L,
    val maxWaitMs: Long = 5_000L
) {
    companion object {
        val DEFAULT = AgentLimits()
    }
}