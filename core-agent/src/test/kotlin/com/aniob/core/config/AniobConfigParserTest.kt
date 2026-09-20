package com.aniob.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobConfigParserTest {

    private val modelsYaml = """
        models:
          - id: phi4-mini-3.8b-q4
            size_gb: 2.1

        generation:
          planner:
            temperature: 0.0
            max_tokens: 512
            json_mode: true
          executor:
            temperature: 0.0
            max_tokens: 128
            json_mode: true
          explorer:
            temperature: 0.4
            max_tokens: 256
            json_mode: false
    """.trimIndent()

    private val agentYaml = """
        agent:
          name: "Aniob"
          max_steps_per_task: 25
          step_timeout_ms: 15000
          max_nodes_per_screen: 60
          watchdog_loop_threshold: 3
    """.trimIndent()

    @Test
    fun `parses per-role generation blocks`() {
        val parsed = AniobConfigParser.parseGeneration(modelsYaml)
        val configs = AniobDecodeConfig.fromEntries(parsed)

        assertEquals(512, configs.getValue(AniobDecodeConfig.Role.PLANNER).maxTokens)
        assertEquals(0.0, configs.getValue(AniobDecodeConfig.Role.EXECUTOR).temperature, 0.0)
        assertEquals(256, configs.getValue(AniobDecodeConfig.Role.EXPLORER).maxTokens)
        assertTrue(configs.getValue(AniobDecodeConfig.Role.PLANNER).jsonMode)
        assertEquals(false, configs.getValue(AniobDecodeConfig.Role.EXPLORER).jsonMode)
    }

    @Test
    fun `roles absent from the file keep compiled defaults`() {
        val configs = AniobDecodeConfig.fromEntries(AniobConfigParser.parseGeneration(modelsYaml))
        assertEquals(
            AniobDecodeConfig.forRole(AniobDecodeConfig.Role.GRILL),
            configs.getValue(AniobDecodeConfig.Role.GRILL)
        )
    }

    @Test
    fun `parses agent limits`() {
        val limits = AniobConfigParser.parseAgentLimits(agentYaml)
        assertEquals(25, limits.maxStepsPerTask)
        assertEquals(15_000L, limits.stepTimeoutMs)
        assertEquals(60, limits.maxNodesPerScreen)
        assertEquals(3, limits.watchdogLoopThreshold)
    }

    @Test
    fun `corrupt config falls back to defaults instead of throwing`() {
        val corrupt = "generation:\n  planner:\n    temperature: not-a-number\n  ::::\n"
        val configs = AniobDecodeConfig.fromEntries(AniobConfigParser.parseGeneration(corrupt))
        assertEquals(AniobDecodeConfig.defaults, configs)
    }

    @Test
    fun `missing generation block leaves defaults untouched`() {
        val parsed = AniobConfigParser.parseGeneration("models:\n  - id: x\n")
        assertTrue(parsed.isEmpty())
        assertEquals(AniobDecodeConfig.defaults, AniobDecodeConfig.fromEntries(parsed))
    }

    @Test
    fun `missing agent block yields compiled default limits`() {
        assertEquals(AgentLimits.DEFAULT, AniobConfigParser.parseAgentLimits("nothing: here"))
    }
}
