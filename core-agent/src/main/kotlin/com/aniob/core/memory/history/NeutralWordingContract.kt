package com.aniob.core.memory.history

/**
 * Plan-4.2 & 4.3: The Neutral-Wording Contract & Fact Priority.
 *
 * Compressed history must be purely observational. Subjective verdicts in history cause future
 * turns to falsely believe a goal was definitively met or permanently broken.
 *
 * ## Why quoted spans are exempt
 * Plan-4.4 requires the summary to "transcribe verbatim any visible text, toast, error banner,
 * code, price, count or account name on the AFTER screen". Android ships toasts that literally
 * read "Download completed" and banners that read "Upload failed". A naive banned-word scan makes
 * those two rules contradict each other: the summarizer is ordered to quote the screen and then
 * punished for quoting it. The resolution is that the ban applies to the AGENT'S OWN VOICE only —
 * text inside quotation marks is an observation of the device, which is exactly the kind of
 * concrete fact this contract exists to preserve.
 */
object NeutralWordingContract {

    /** Banned in the agent's own voice. Quoted device text is exempt (see class docs). */
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

    /** Third-person self-reference; history is written first person. */
    private val THIRD_PERSON = listOf("the agent", "the assistant", "the model", "the bot")

    /** Matches "..." and '...' spans, including the smart-quote variants a model emits. */
    private val QUOTED_SPAN = Regex("\"[^\"]*\"|\u201C[^\u201D]*\u201D|'[^']*'|\u2018[^\u2019]*\u2019")

    /**
     * Plan-4.3: Fact priority hierarchy when conflicting evidence exists.
     * Lower rank wins. Used by the assembler when two sources disagree about the same slot.
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

    /** Resolves a conflict between two sourced claims, preferring the more authoritative source. */
    fun <T> resolveConflict(a: Pair<FactPriority, T>, b: Pair<FactPriority, T>): T =
        if (a.first.isMoreAuthoritativeThan(b.first)) a.second else b.second

    data class ValidationReport(
        val isValid: Boolean,
        val violations: List<String>
    ) {
        /**
         * Regeneration feedback handed back to the summarizer. Plan-4.5 says "regenerate on
         * violation"; a regeneration that cannot see the violation just reproduces it.
         */
        fun asRegenerationFeedback(): String =
            if (isValid) "" else buildString {
                appendLine("Your previous summary was rejected. Fix ALL of these and rewrite it:")
                violations.forEach { appendLine("- $it") }
                appendLine("Rules: purely observational, first person, no verdict words.")
                appendLine("Quote on-screen text verbatim inside \"double quotes\" — quoted device text is allowed.")
            }.trim()
    }

    /**
     * Validates the agent's own voice. Quoted spans are removed before scanning so verbatim
     * device text never trips the ban.
     */
    fun validate(text: String): ValidationReport {
        val violations = mutableListOf<String>()
        val unquoted = QUOTED_SPAN.replace(text, " ").lowercase()

        for (banned in BANNED_WORDS) {
            val regex = "\\b${Regex.escape(banned)}\\b".toRegex()
            if (regex.containsMatchIn(unquoted)) {
                violations.add(
                    "Found banned subjective phrase: '$banned'. " +
                        "State the observation instead (e.g. \"the settings page was displayed\"), " +
                        "or quote it verbatim if it is text shown on screen."
                )
            }
        }

        THIRD_PERSON.firstOrNull { unquoted.contains(it) }?.let {
            violations.add(
                "Third-person reference found ('$it'). Must use first-person ('I tapped...')."
            )
        }

        return ValidationReport(isValid = violations.isEmpty(), violations = violations)
    }

    fun assertNeutral(text: String) {
        val report = validate(text)
        require(report.isValid) {
            "Neutral wording contract violated:\n" + report.violations.joinToString("\n")
        }
    }

    /**
     * ABSENCE IS A FACT. Never describe an expected element merely because it was expected.
     * Produces the sanctioned phrasing so callers stop inventing their own.
     */
    fun absence(expected: String): String = "I did not see $expected on this screen."

    /**
     * Rewrites MACHINE-GENERATED strings (verifier reasons, dispatch errors, gate messages) into
     * contract-clean wording before they enter history.
     *
     * This is deliberately NOT for model output — a model that breaks the contract must be told
     * and made to rewrite, because silently laundering its verdict hides the fact that it reached
     * one. Machine strings have no such problem: nobody is fooled by rephrasing our own error text.
     */
    private val SUBSTITUTIONS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bunsuccessful\\b") to "without the expected result",
        Regex("(?i)\\bsuccessfully\\b") to "",
        Regex("(?i)\\bfailed to\\b") to "did not",
        Regex("(?i)\\bfailed\\b") to "did not produce the expected result",
        Regex("(?i)\\bcould not\\b") to "did not",
        Regex("(?i)\\bimpossible\\b") to "not available",
        Regex("(?i)\\bcompleted\\b") to "finished",
        Regex("(?i)\\bachieved\\b") to "reached",
        Regex("(?i)\\bnavigated to\\b") to "moved to",
        Regex("(?i)\\barrived at\\b") to "reached",
        Regex("(?i)\\bentered\\b") to "typed into",
        Regex("(?i)\\bthe agent\\b") to "I",
        Regex("(?i)\\bthe assistant\\b") to "I"
    )

    fun neutralize(text: String): String {
        var out = text
        SUBSTITUTIONS.forEach { (re, replacement) -> out = re.replace(out, replacement) }
        return out.replace(Regex("\\s{2,}"), " ").trim()
    }
}
