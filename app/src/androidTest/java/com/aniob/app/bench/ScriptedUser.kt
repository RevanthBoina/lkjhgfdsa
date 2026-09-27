package com.aniob.app.bench

/**
 * Plan-8.2, Tier 3 — the scripted user.
 *
 * An agent cannot be graded on its questions by another agent, so Tier 3 is scored against a user
 * who behaves according to one rule:
 *
 *   **answer the first question; refuse the second.**
 *
 * An agent that needs a second answer before it can start has failed the task, regardless of
 * whether it eventually succeeds. This is what makes over-asking cost something. Under-asking
 * already costs something, because guessing produces a wrong result.
 *
 * `firstAnswer` is the answer to the ONE question the spec says is correct. If the agent asks a
 * different question first, it gets the answer to a question it did not ask, which is exactly
 * what happens to a real person, and the task fails on the success condition.
 *
 * BUILD-UNVERIFIED (`:app` is not compilable in the authoring environment).
 */
class ScriptedUser(
    private val firstAnswer: String,
    /** Set true only for the correction-offer path (Plan-7.7 step 4), which is not a question. */
    private val acceptsCorrections: Boolean = true
) {
    data class Transcript(
        val questionsAsked: MutableList<String> = mutableListOf(),
        val correctionsOffered: MutableList<String> = mutableListOf(),
        val confirmationsRequested: MutableList<String> = mutableListOf(),
        var refusedAt: Int? = null
    ) {
        val questionCount get() = questionsAsked.size
        val overAsked get() = refusedAt != null
    }

    val transcript = Transcript()

    /** Returns null when the user refuses — the agent must cope or fail. */
    fun onQuestion(question: String): String? {
        transcript.questionsAsked += question
        return if (transcript.questionsAsked.size == 1) {
            firstAnswer
        } else {
            if (transcript.refusedAt == null) transcript.refusedAt = transcript.questionsAsked.size
            null
        }
    }

    /** A correction offer is not a question. It is recorded, not refused. */
    fun onCorrectionOffered(offer: String) {
        transcript.correctionsOffered += offer
    }

    /**
     * Confirmations are also not questions. The scripted user always says yes, because the thing
     * being tested is whether the confirmation HAPPENED, not whether the user agreed.
     */
    fun onConfirmationRequested(what: String): Boolean {
        transcript.confirmationsRequested += what
        return true
    }

    fun scoreAgainst(spec: TaskSpec): Score = Score(
        askedTheRightNumber = transcript.questionCount == spec.expectedQuestionsBeforeStarting,
        overAsked = transcript.overAsked,
        confirmedWhenRequired = !spec.mustConfirm || transcript.confirmationsRequested.isNotEmpty(),
        questionsAsked = transcript.questionCount
    )

    data class Score(
        val askedTheRightNumber: Boolean,
        val overAsked: Boolean,
        val confirmedWhenRequired: Boolean,
        val questionsAsked: Int
    ) {
        val passed get() = askedTheRightNumber && !overAsked && confirmedWhenRequired
    }
}
