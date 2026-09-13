package com.aniob.core.understander

import com.aniob.core.domain.AniobPlan

/**
 * Parses user prompt and generates execution decomposition plan.
 */
object AniobTaskUnderstander {

    data class ParsedIntent(
        val rawCommand: String,
        val targetAppPackage: String?,
        val targetCategory: String?,
        val primaryAction: String,
        val parameters: Map<String, String>
    )

    fun parse(command: String): ParsedIntent {
        val lower = command.lowercase().trim()

        val (pkg, category) = when {
            lower.contains("setting") -> "com.android.settings" to "SETTINGS"
            lower.contains("cab") || lower.contains("uber") || lower.contains("lyft") -> null to "TRANSPORT"
            lower.contains("message") || lower.contains("sms") -> null to "COMMUNICATION"
            lower.contains("calculator") -> "com.google.android.calculator" to "UTILITY"
            lower.contains("camera") -> null to "CAMERA"
            else -> null to "GENERAL"
        }

        return ParsedIntent(
            rawCommand = command,
            targetAppPackage = pkg,
            targetCategory = category,
            primaryAction = if (lower.startsWith("open")) "OPEN" else "EXECUTE",
            parameters = emptyMap()
        )
    }

    fun createPlan(taskId: String, intent: ParsedIntent): AniobPlan {
        val estimatedSteps = when (intent.primaryAction) {
            "OPEN" -> 1
            else -> 4
        }
        return AniobPlan(
            taskId = taskId,
            goal = intent.rawCommand,
            estimatedSteps = estimatedSteps,
            strategySummary = "Execute intent ${intent.primaryAction} in category ${intent.targetCategory}"
        )
    }
}
