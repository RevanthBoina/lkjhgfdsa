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
    suspend fun evaluateTask(
        taskPrompt: String,
        rememberedAnswers: Map<String, String> = emptyMap()
    ): AniobGrillMeResult {
        val trimmed = taskPrompt.trim()

        // Deterministic check for simple self-contained intent commands
        if (isSelfContainedIntent(trimmed)) {
            return AniobGrillMeResult(needsClarification = false)
        }

        // Use LLM client if available
        if (llmClient != null) {
            return try {
                val res = llmClient.generateClarificationQuestions(trimmed)
                val filtered = res.questions.filter { q ->
                    !rememberedAnswers.containsKey(q.id) && !rememberedAnswers.containsKey("grill.${q.id}")
                }
                if (filtered.isEmpty()) AniobGrillMeResult(needsClarification = false)
                else res.copy(questions = filtered)
            } catch (e: Exception) {
                generateFallbackQuestions(trimmed, rememberedAnswers)
            }
        }

        // Dynamic fallback generator
        return generateFallbackQuestions(trimmed, rememberedAnswers)
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
     * Extracts explicit values from prompt first, then checks remembered answers.
     */
    fun generateFallbackQuestions(
        taskPrompt: String,
        rememberedAnswers: Map<String, String> = emptyMap()
    ): AniobGrillMeResult {
        val lower = taskPrompt.lowercase().trim()
        val questions = mutableListOf<GrillQuestion>()

        fun isRemembered(id: String): Boolean =
            rememberedAnswers.containsKey(id) || rememberedAnswers.containsKey("grill.$id")

        if (lower.contains("cab") || lower.contains("ride") || lower.contains("taxi")) {
            val destMatch = Regex("\\b(?:to|destination\\s+is)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:via|using|by|with)|$)").find(lower)
            val hasExplicitDest = destMatch != null && destMatch.groupValues[1].trim().isNotBlank() &&
                !destMatch.groupValues[1].trim().startsWith("a ") && !destMatch.groupValues[1].trim().startsWith("the ")
            val knownDest = hasExplicitDest || isRemembered("destination")
            if (!knownDest) {
                questions.add(
                    GrillQuestion(
                        id = "destination",
                        question = "What is your destination?",
                        options = listOf("Home", "Office", "Airport", "Downtown"),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            if (!isRemembered("ride_type")) {
                questions.add(
                    GrillQuestion(
                        id = "ride_type",
                        question = "Which ride tier do you prefer?",
                        options = listOf("Cheapest", "Standard Comfort", "XL / 6 Seats", "Fastest Arrival"),
                        allowFreeText = false,
                        defaultOption = "Cheapest"
                    )
                )
            }
            if (!isRemembered("provider_app")) {
                questions.add(
                    GrillQuestion(
                        id = "provider_app",
                        question = "Preferred provider app?",
                        options = listOf("Uber", "Lyft", "Auto-select cheapest"),
                        allowFreeText = false,
                        defaultOption = "Auto-select cheapest"
                    )
                )
            }
        } else if (lower.contains("message") || lower.contains("text") || lower.contains("send")) {
            val toMatch = Regex("(?:message|text|send(?:\\s+a)?\\s+message\\s+to|send\\s+to)\\s+([a-zA-Z0-9_]+)").find(lower)
            val hasExplicitRecipient = toMatch != null && toMatch.groupValues[1].trim().isNotBlank() &&
                toMatch.groupValues[1].trim() !in listOf("a", "the", "me", "my", "app")
            val knownRecipient = hasExplicitRecipient || isRemembered("recipient")
            if (!knownRecipient) {
                questions.add(
                    GrillQuestion(
                        id = "recipient",
                        question = "Who would you like to message?",
                        options = listOf("Mom", "Work Group", "Recent Contact"),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            val hasContent = lower.contains("saying") || lower.contains("\"") || lower.contains("'") ||
                Regex("(?:text|message)\\s+[a-zA-Z0-9_]+\\s+(?:that\\s+)?(.+)").find(lower)?.groupValues?.get(1)?.isNotBlank() == true
            if (!hasContent && !isRemembered("message_content")) {
                questions.add(
                    GrillQuestion(
                        id = "message_content",
                        question = "What message would you like to send?",
                        options = emptyList(),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            val hasApp = lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("sms") || lower.contains("messages")
            if (!hasApp && !isRemembered("app")) {
                questions.add(
                    GrillQuestion(
                        id = "app",
                        question = "Which messaging app?",
                        options = listOf("SMS / Messages", "WhatsApp", "Telegram"),
                        allowFreeText = false,
                        defaultOption = "SMS / Messages"
                    )
                )
            }
        } else if (lower.contains("alarm") || lower.contains("timer")) {
            val timeMatch = Regex("\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|in\\s+\\d+\\s+minutes?)\\b").find(lower)
            val hasExplicitTime = timeMatch != null && timeMatch.value.isNotBlank()
            val knownTime = hasExplicitTime || isRemembered("time")
            if (!knownTime) {
                questions.add(
                    GrillQuestion(
                        id = "time",
                        question = "What time should the alarm be set for?",
                        options = listOf("7:00 AM", "8:00 AM", "In 15 minutes"),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
        }

        return if (questions.isNotEmpty()) {
            AniobGrillMeResult(needsClarification = true, questions = questions)
        } else {
            AniobGrillMeResult(needsClarification = false)
        }
    }
}
