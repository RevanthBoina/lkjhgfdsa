package com.aniob.core.config

/**
 * Parses the Aniob `configs` YAML and prompt payloads.
 *
 * Pure JVM (no Android, no YAML library): the files use a shallow, hand-written subset —
 * two-space-indented `key: value` pairs plus one nesting level — so a targeted line parser is
 * both sufficient and dependency-free. Anything unparseable is skipped rather than throwing:
 * a corrupt config asset must degrade to compiled defaults, never crash task start.
 */
object AniobConfigParser {

    /**
     * Reads the `generation:` block of `models.yaml`.
     *
     * ```yaml
     * generation:
     *   planner:
     *     temperature: 0.0
     *     max_tokens: 512
     *     json_mode: true
     * ```
     */
    fun parseGeneration(yaml: String): Map<String, AniobDecodeConfig.RoleGenParams> {
        val result = mutableMapOf<String, AniobDecodeConfig.RoleGenParams>()
        var inGeneration = false
        var currentRole: String? = null
        var temperature: Double? = null
        var maxTokens: Int? = null
        var jsonMode: Boolean? = null

        fun flush() {
            val role = currentRole ?: return
            result[role] = AniobDecodeConfig.RoleGenParams(temperature, maxTokens, jsonMode)
            temperature = null
            maxTokens = null
            jsonMode = null
        }

        yaml.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#')
            if (line.isBlank()) return@forEach
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()

            if (indent == 0) {
                flush()
                currentRole = null
                inGeneration = trimmed.equals("generation:", ignoreCase = true)
                return@forEach
            }
            if (!inGeneration) return@forEach

            if (indent <= 2) {
                flush()
                currentRole = trimmed.removeSuffix(":").trim().takeIf { trimmed.endsWith(":") }
                return@forEach
            }
            val (key, value) = splitPair(trimmed) ?: return@forEach
            when (key.lowercase().replace('-', '_')) {
                "temperature", "temp" -> value.toDoubleOrNull()?.let { temperature = it }
                "max_tokens", "maxtokens" -> value.toIntOrNull()?.let { maxTokens = it }
                "json_mode", "jsonmode" -> value.toBooleanStrictOrNull()?.let { jsonMode = it }
            }
        }
        flush()
        return result
    }

    /** Reads the flat `agent:` block of `agent.yaml` into [AgentLimits]. */
    fun parseAgentLimits(yaml: String): AgentLimits {
        val defaults = AgentLimits.DEFAULT
        val values = flatBlock(yaml, "agent")
        return AgentLimits(
            maxStepsPerTask = values["max_steps_per_task"]?.toIntOrNull() ?: defaults.maxStepsPerTask,
            stepTimeoutMs = values["step_timeout_ms"]?.toLongOrNull() ?: defaults.stepTimeoutMs,
            maxNodesPerScreen = values["max_nodes_per_screen"]?.toIntOrNull() ?: defaults.maxNodesPerScreen,
            watchdogLoopThreshold = values["watchdog_loop_threshold"]?.toIntOrNull()
                ?: defaults.watchdogLoopThreshold,
            settleTimeoutMs = values["settle_timeout_ms"]?.toLongOrNull() ?: defaults.settleTimeoutMs,
            minWaitMs = values["min_wait_ms"]?.toLongOrNull() ?: defaults.minWaitMs,
            maxWaitMs = values["max_wait_ms"]?.toLongOrNull() ?: defaults.maxWaitMs
        )
    }

    /** Reads every `key: value` directly under `blockName:` (one nesting level). */
    private fun flatBlock(yaml: String, blockName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var inBlock = false
        yaml.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#')
            if (line.isBlank()) return@forEach
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()

            if (indent == 0) {
                inBlock = trimmed.equals("$blockName:", ignoreCase = true)
                return@forEach
            }
            if (!inBlock || indent > 2) return@forEach
            val (key, value) = splitPair(trimmed) ?: return@forEach
            result[key.lowercase().replace('-', '_')] = unquote(value)
        }
        return result
    }

    private fun splitPair(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (key.isEmpty()) return null
        return key to value
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("\"").removeSurrounding("'").trim()
}
