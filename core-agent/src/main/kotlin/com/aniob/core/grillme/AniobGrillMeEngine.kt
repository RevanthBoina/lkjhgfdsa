package com.aniob.core.grillme

/**
 * Grill-Me Dynamic Clarification Data Models & Engine.
 */
data class GrillQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val allowFreeText: Boolean = true,
    val defaultOption: String? = null
)

data class AniobGrillMeResult(
    val needsClarification: Boolean,
    val questions: List<GrillQuestion> = emptyList(),
    val confidence: Float = 1.0f
)

interface GrillMeLlmClient {
    suspend fun generateClarificationQuestions(taskPrompt: String): AniobGrillMeResult
}

class AniobGrillMeEngine(
    private val llmClient: GrillMeLlmClient? = null
) {
    /**
     * Checks if task is ambiguous and generates structured questions + options + free text.
     * Flow: Understand -> Ask questions (if required) -> Create plan -> Select easiest path -> Proceed.
     */
    suspend fun evaluateTask(taskPrompt: String): AniobGrillMeResult {
        val trimmed = taskPrompt.trim()

        // Deterministic check for simple self-contained intent commands
        if (isSelfContainedIntent(trimmed)) {
            return AniobGrillMeResult(needsClarification = false)
        }

        // Use LLM client if available
        if (llmClient != null) {
            return try {
                llmClient.generateClarificationQuestions(trimmed)
            } catch (e: Exception) {
                generateFallbackQuestions(trimmed)
            }
        }

        // Dynamic fallback generator
        return generateFallbackQuestions(trimmed)
    }

    private fun isSelfContainedIntent(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return lower.startsWith("open settings") ||
                lower.startsWith("turn on flashlight") ||
                lower.startsWith("check battery") ||
                lower.startsWith("open calculator") ||
                lower.startsWith("open camera")
    }

    /**
     * Generates structured questions for common ambiguous task patterns.
     */
    fun generateFallbackQuestions(taskPrompt: String): AniobGrillMeResult {
        val lower = taskPrompt.lowercase()
        return when {
            lower.contains("cab") || lower.contains("ride") || lower.contains("taxi") -> {
                AniobGrillMeResult(
                    needsClarification = true,
                    questions = listOf(
                        GrillQuestion(
                            id = "destination",
                            question = "What is your destination?",
                            options = listOf("Home", "Office", "Airport", "Downtown"),
                            allowFreeText = true,
                            defaultOption = "Office"
                        ),
                        GrillQuestion(
                            id = "ride_type",
                            question = "Which ride tier do you prefer?",
                            options = listOf("Cheapest", "Standard Comfort", "XL / 6 Seats", "Fastest Arrival"),
                            allowFreeText = false,
                            defaultOption = "Cheapest"
                        ),
                        GrillQuestion(
                            id = "provider_app",
                            question = "Preferred provider app?",
                            options = listOf("Uber", "Lyft", "Auto-select cheapest"),
                            allowFreeText = false,
                            defaultOption = "Auto-select cheapest"
                        )
                    )
                )
            }
            lower.contains("message") || lower.contains("text") || lower.contains("send") -> {
                AniobGrillMeResult(
                    needsClarification = true,
                    questions = listOf(
                        GrillQuestion(
                            id = "recipient",
                            question = "Who would you like to message?",
                            options = listOf("Mom", "Work Group", "Recent Contact"),
                            allowFreeText = true,
                            defaultOption = "Recent Contact"
                        ),
                        GrillQuestion(
                            id = "app",
                            question = "Which messaging app?",
                            options = listOf("SMS / Messages", "WhatsApp", "Telegram"),
                            allowFreeText = false,
                            defaultOption = "SMS / Messages"
                        )
                    )
                )
            }
            lower.contains("alarm") || lower.contains("timer") -> {
                AniobGrillMeResult(
                    needsClarification = true,
                    questions = listOf(
                        GrillQuestion(
                            id = "time",
                            question = "What time should the alarm be set for?",
                            options = listOf("7:00 AM", "8:00 AM", "In 15 minutes"),
                            allowFreeText = true,
                            defaultOption = "7:00 AM"
                        )
                    )
                )
            }
            else -> {
                AniobGrillMeResult(
                    needsClarification = false
                )
            }
        }
    }
}
