package com.aniob.core.skills

import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AniobSkillCodecTest {

    private val sampleSkill = AniobSkill(
        name = "toggle_dark_theme",
        version = "2.1",
        description = "Toggles system dark mode setting",
        riskTier = "LOW",
        confirmationRequired = false,
        status = SkillStatus.ACTIVE,
        triggerKeywords = listOf("dark mode", "enable dark theme"),
        steps = listOf(
            SkillStep(
                stepIndex = 1,
                actionType = "open_app",
                packageName = "com.android.settings",
                description = "Settings home"
            ),
            SkillStep(
                stepIndex = 2,
                actionType = "tap",
                target = SemanticTarget.Text("Display", exact = false),
                description = "Tap display"
            )
        )
    )

    @Test
    fun `round trips a valid skill through json encoding and decoding`() {
        val json = AniobSkillCodec.encode(sampleSkill)
        val decoded = AniobSkillCodec.decode(json)

        assertEquals(sampleSkill.name, decoded.name)
        assertEquals(sampleSkill.description, decoded.description)
        assertEquals(sampleSkill.version, decoded.version)
        assertEquals(SkillStatus.ACTIVE, decoded.status)
        assertEquals(2, decoded.steps.size)
        assertEquals("open_app", decoded.steps[0].actionType)
        assertEquals("tap", decoded.steps[1].actionType)
        assertEquals(SemanticTarget.Text("Display", exact = false), decoded.steps[1].target)
    }

    @Test
    fun `decodes yaml formatted skill`() {
        val yaml = """
            name: "test_skill"
            description: "Test description"
            version: "2.1"
            status: "active"
            triggers:
              - "test"
            steps:
              - step_index: 1
                action: "tap"
                target:
                  kind: "text"
                  value: "Submit"
        """.trimIndent()

        val decoded = AniobSkillCodec.decode(yaml)
        assertEquals("test_skill", decoded.name)
        assertEquals("Test description", decoded.description)
        assertEquals(SkillStatus.ACTIVE, decoded.status)
        assertEquals(1, decoded.steps.size)
        assertEquals("tap", decoded.steps[0].actionType)
        assertEquals(SemanticTarget.Text("Submit", exact = false), decoded.steps[0].target)
    }

    @Test
    fun `parses draft status correctly`() {
        val yaml = """
            name: "draft_skill"
            description: "Draft skill under development"
            status: "draft"
            steps:
              - step_index: 1
                action: "tap"
                target:
                  kind: "text"
                  value: "Button"
        """.trimIndent()

        val decoded = AniobSkillCodec.decode(yaml)
        assertEquals(SkillStatus.DRAFT, decoded.status)
        assertTrue(decoded.isDraft)
    }

    @Test
    fun `rejects unsupported version`() {
        val json = """
            {
                "name": "future_skill",
                "description": "Future version",
                "version": "99.0",
                "steps": [
                    { "step_index": 1, "action": "tap", "target": { "kind": "text", "value": "Go" } }
                ]
            }
        """.trimIndent()

        try {
            AniobSkillCodec.decode(json)
            fail("Should reject version 99.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported skill version") == true)
        }
    }

    @Test
    fun `rejects empty name or missing steps`() {
        val invalidYaml = """
            description: "Missing name"
            steps: []
        """.trimIndent()

        try {
            AniobSkillCodec.decode(invalidYaml)
            fail("Should reject missing name and empty steps")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }
}
