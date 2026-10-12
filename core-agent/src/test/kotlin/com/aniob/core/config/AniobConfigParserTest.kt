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

    @Test
    fun `validateCatalogDefaults passes on current valid models YAML`() {
        val candidates = listOf(
            java.io.File("../configs/models.yaml"),
            java.io.File("configs/models.yaml")
        )
        val fileContent = candidates.firstOrNull { it.exists() }?.readText()
            ?: """
                models:
                  - id: qwen2.5-1.5b-q8
                  - id: gemma4-e2b
                  - id: gemma4-e4b
                  - id: phi4-mini-q8
                defaults:
                  ram_4gb: "qwen2.5-1.5b-q8"
                  ram_6gb: "gemma4-e2b"
                  ram_8gb: "phi4-mini-q8"
                  ram_8gb_coding: "qwen2.5-1.5b-q8"
                  ram_8gb_balanced: "gemma4-e4b"
            """.trimIndent()
        val result = AniobConfigParser.validateCatalogDefaults(fileContent)
        assertTrue(result.errors.joinToString(), result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validateCatalogDefaults fails when default references undeclared model ID`() {
        val yamlWithUnknownDefault = """
            models:
              - id: qwen2.5-1.5b-q8
              - id: gemma4-e2b
            defaults:
              ram_4gb: "qwen2.5-1.5b-q8"
              ram_6gb: "non-existent-model-id"
              ram_8gb: "qwen2.5-1.5b-q8"
              ram_8gb_coding: "qwen2.5-1.5b-q8"
              ram_8gb_balanced: "gemma4-e2b"
        """.trimIndent()
        val result = AniobConfigParser.validateCatalogDefaults(yamlWithUnknownDefault)
        assertEquals(false, result.isValid)
        assertTrue(result.errors.any { it.contains("non-existent-model-id") })
    }

    @Test
    fun `validateCatalogDefaults fails when defaults block is missing or empty`() {
        val missingDefaults = """
            models:
              - id: qwen2.5-1.5b-q8
        """.trimIndent()
        val resultMissing = AniobConfigParser.validateCatalogDefaults(missingDefaults)
        assertEquals(false, resultMissing.isValid)
        assertTrue(resultMissing.errors.any { it.contains("no 'defaults:' block") })

        val emptyDefaults = """
            models:
              - id: qwen2.5-1.5b-q8
            defaults:
        """.trimIndent()
        val resultEmpty = AniobConfigParser.validateCatalogDefaults(emptyDefaults)
        assertEquals(false, resultEmpty.isValid)
    }

    @Test
    fun `validateCatalogDefaults fails when required default key is missing or blank`() {
        val missingKey = """
            models:
              - id: qwen2.5-1.5b-q8
              - id: gemma4-e2b
            defaults:
              ram_4gb: "qwen2.5-1.5b-q8"
              ram_6gb: "gemma4-e2b"
              ram_8gb: "qwen2.5-1.5b-q8"
              ram_8gb_balanced: "gemma4-e2b"
        """.trimIndent()
        val result = AniobConfigParser.validateCatalogDefaults(missingKey)
        assertEquals(false, result.isValid)
        assertTrue(result.errors.any { it.contains("ram_8gb_coding") })
    }
}
