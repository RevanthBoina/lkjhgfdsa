package com.aniob.core.skills

import com.aniob.core.domain.SemanticTarget
import java.io.InputStream

/**
 * Loads, parses, and manages declarative YAML skills (Skill Format v2.1).
 * Pure JVM (no android.* dependencies).
 */
class AniobSkillEngine {

    private val loadedSkills = mutableListOf<AniobSkill>()

    init {
        loadDefaultBuiltinSkills()
    }

    fun getLoadedSkills(): List<AniobSkill> = loadedSkills.toList()

    fun registerSkill(skill: AniobSkill) {
        loadedSkills.removeAll { it.name.equals(skill.name, ignoreCase = true) }
        loadedSkills.add(skill)
    }

    /**
     * Parses standard YAML skill v2.1 text.
     */
    fun parseYaml(yamlContent: String): AniobSkill {
        var name = "unknown_skill"
        var version = "2.1"
        var description = ""
        val triggerKeywords = mutableListOf<String>()
        var intentPattern = ""
        var riskTier = "LOW"
        var confirmationRequired = false
        var targetPackage: String? = null
        val slots = mutableMapOf<String, String>()
        val steps = mutableListOf<SkillStep>()

        var inTriggers = false
        var inSteps = false
        var inSlots = false

        var currentStepIdx = 1
        var currentAction = "tap"
        var currentSelector: String? = null
        var currentTargetId: Int? = null
        var currentTarget: SemanticTarget? = null
        var currentText: String? = null
        var currentPkg: String? = null
        var currentKey: String? = null
        var currentDirection: String? = null
        var currentDesc = ""

        // v2.1 target block accumulator
        var inTargetBlock = false
        var targetKind: String? = null
        var targetValue: String? = null
        var targetExact = false

        fun flushTarget() {
            if (inTargetBlock && targetKind != null && targetValue != null) {
                currentTarget = when (targetKind!!.lowercase()) {
                    "som_index", "som", "index" -> targetValue!!.toIntOrNull()?.let { SemanticTarget.SomIndex(it) }
                    "resource_id", "id", "view_id" -> SemanticTarget.ResourceId(targetValue!!)
                    "text" -> SemanticTarget.Text(targetValue!!, targetExact)
                    "content_desc", "content_description", "desc" -> SemanticTarget.ContentDesc(targetValue!!, targetExact)
                    else -> null
                }
            }
            inTargetBlock = false
            targetKind = null
            targetValue = null
            targetExact = false
        }

        fun flushStep() {
            if (currentAction.isNotBlank()) {
                flushTarget()
                steps.add(
                    SkillStep(
                        stepIndex = currentStepIdx,
                        actionType = currentAction,
                        selector = currentSelector,
                        targetNodeId = currentTargetId,
                        target = currentTarget,
                        text = currentText,
                        packageName = currentPkg,
                        key = currentKey,
                        direction = currentDirection,
                        description = currentDesc
                    )
                )
                currentStepIdx++
                currentAction = "tap"
                currentSelector = null
                currentTargetId = null
                currentTarget = null
                currentText = null
                currentPkg = null
                currentKey = null
                currentDirection = null
                currentDesc = ""
            }
        }

        yamlContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#") || line.isBlank()) return@forEach

            when {
                line.startsWith("name:") -> name = line.substringAfter("name:").trim().removeSurrounding("\"")
                line.startsWith("version:") -> version = line.substringAfter("version:").trim().removeSurrounding("\"")
                line.startsWith("description:") -> description = line.substringAfter("description:").trim().removeSurrounding("\"")
                line.startsWith("risk_tier:") -> riskTier = line.substringAfter("risk_tier:").trim().uppercase()
                line.startsWith("confirmation_required:") -> confirmationRequired = line.substringAfter("confirmation_required:").trim().toBoolean()
                line.startsWith("target_package:") -> targetPackage = line.substringAfter("target_package:").trim()
                line.startsWith("intent_pattern:") -> intentPattern = line.substringAfter("intent_pattern:").trim()

                line.startsWith("triggers:") -> {
                    inTriggers = true
                    inSteps = false
                    inSlots = false
                }
                line.startsWith("slots:") -> {
                    inSlots = true
                    inTriggers = false
                    inSteps = false
                }
                line.startsWith("steps:") -> {
                    inSteps = true
                    inTriggers = false
                    inSlots = false
                }

                inTriggers && line.startsWith("- ") -> {
                    triggerKeywords.add(line.removePrefix("- ").trim().removeSurrounding("\"").lowercase())
                }
                inSlots && line.contains(":") -> {
                    val k = line.substringBefore(":").trim()
                    val v = line.substringAfter(":").trim()
                    slots[k] = v
                }
                inSteps && line.startsWith("- step:") -> {
                    if (steps.isNotEmpty() || currentDesc.isNotBlank() || currentPkg != null) {
                        flushStep()
                    }
                }
                inSteps && line.startsWith("action:") -> {
                    flushTarget()
                    currentAction = line.substringAfter("action:").trim()
                }
                inSteps && line.startsWith("package:") -> currentPkg = line.substringAfter("package:").trim()
                inSteps && line.startsWith("target_id:") -> currentTargetId = line.substringAfter("target_id:").trim().toIntOrNull()
                // v2.1 semantic target block
                inSteps && line == "target:" -> {
                    flushTarget()
                    inTargetBlock = true
                }
                inTargetBlock && line.startsWith("kind:") ->
                    targetKind = line.substringAfter("kind:").trim().removeSurrounding("\"")
                inTargetBlock && line.startsWith("value:") ->
                    targetValue = line.substringAfter("value:").trim().removeSurrounding("\"")
                inTargetBlock && line.startsWith("exact:") ->
                    targetExact = line.substringAfter("exact:").trim().toBoolean()
                inSteps && line.startsWith("key:") -> currentKey = line.substringAfter("key:").trim().removeSurrounding("\"")
                inSteps && line.startsWith("direction:") -> currentDirection = line.substringAfter("direction:").trim().removeSurrounding("\"")
                inSteps && line.startsWith("text:") -> currentText = line.substringAfter("text:").trim().removeSurrounding("\"")
                inSteps && line.startsWith("description:") -> currentDesc = line.substringAfter("description:").trim().removeSurrounding("\"")
            }
        }
        if (inSteps) {
            flushStep()
        }

        return AniobSkill(
            name = name,
            version = version,
            description = description,
            triggerKeywords = triggerKeywords,
            intentPattern = intentPattern,
            riskTier = riskTier,
            confirmationRequired = confirmationRequired,
            targetPackage = targetPackage,
            slots = slots,
            steps = steps,
            isDraft = false
        )
    }

    fun loadFromStream(inputStream: InputStream): AniobSkill {
        val content = inputStream.bufferedReader().use { it.readText() }
        val skill = parseYaml(content)
        registerSkill(skill)
        return skill
    }

    private fun loadDefaultBuiltinSkills() {
        // Built-in foundational declarative skills
        registerSkill(
            AniobSkill(
                name = "open_settings",
                version = "2.1",
                description = "Directly navigates to Android system settings",
                triggerKeywords = listOf("open settings", "system settings", "settings app", "preferences"),
                riskTier = "LOW",
                confirmationRequired = false,
                targetPackage = "com.android.settings",
                steps = listOf(
                    SkillStep(1, "open_app", packageName = "com.android.settings", description = "Launch settings app"),
                    SkillStep(2, "finish", description = "Settings opened successfully")
                )
            )
        )

        registerSkill(
            AniobSkill(
                name = "send_quick_message",
                version = "2.1",
                description = "Compose and send a message via SMS/Chat with confirmation gate",
                triggerKeywords = listOf("send message", "send text", "message to", "sms"),
                riskTier = "HIGH",
                confirmationRequired = true,
                targetPackage = "com.google.android.apps.messaging",
                steps = listOf(
                    SkillStep(1, "open_app", packageName = "com.google.android.apps.messaging", description = "Launch messages"),
                    SkillStep(2, "confirm_with_user", description = "Confirm sending message to recipient"),
                    SkillStep(3, "finish", description = "Message sent")
                )
            )
        )
    }
}
