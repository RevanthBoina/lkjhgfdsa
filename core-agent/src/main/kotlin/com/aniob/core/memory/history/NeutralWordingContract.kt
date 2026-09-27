package com.aniob.core.memory.history

/**
 * Plan-4.2 & 4.3: The Neutral-Wording Contract & Fact Priority.
 * Compressed history must be purely observational without subjective verdicts.
 */
object NeutralWordingContract {

    val BANNED_WORDS = listOf(
        "successfully",
        "completed",
        "achieved",
        "entered",
        "navigated to",
        "arrived at",
        "failed",
        "unsuccessful",
        "could not",
        "impossible"
    )

    /**
     * Plan-4.3: Fact priority hierarchy when conflicting evidence exists.
     */
    enum class FactPriority(val rank: Int) {
        USER_INSTRUCTION(1),
        CONTROLLER_RESULT(2),
        TOOL_RESULT(3),
        UI_OCR_DELTA(4),
        VISUAL_TRANSITION_SUMMARY(5),
        AGENT_REASONING(6);

        fun isMoreAuthoritativeThan(other: FactPriority): Boolean = this.rank < other.rank
    }

    data class ValidationReport(
        val isValid: Boolean,
        val violations: List<String>
    )

    /**
     * Validates that text complies with the neutral wording contract:
     * - No banned subjective words
     * - First person voice ("I tapped", not "the agent")
     */
    fun validate(text: String): ValidationReport {
        val violations = mutableListOf<String>()
        val lower = text.lowercase()

        for (banned in BANNED_WORDS) {
            val regex = "\\b${Regex.escape(banned)}\\b".toRegex()
            if (regex.containsMatchIn(lower)) {
                violations.add("Found banned subjective phrase: '$banned'")
            }
        }

        if (lower.contains("the agent") || lower.contains("the assistant")) {
            violations.add("Third-person reference found ('the agent'/'the assistant'). Must use first-person ('I tapped...').")
        }

        return ValidationReport(
            isValid = violations.isEmpty(),
            violations = violations
        )
    }

    /**
     * Sanitize or check neutral statement substitution.
     */
    fun assertNeutral(text: String) {
        val report = validate(text)
        require(report.isValid) {
            "Neutral wording contract violated:\n" + report.violations.joinToString("\n")
        }
    }
}
