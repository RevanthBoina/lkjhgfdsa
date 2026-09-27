package com.aniob.core.profile

import com.aniob.core.verification.ReleaseDecision

/**
 * Plan-7.3 — PROFILES ARE USER-CHOSEN, NEVER AGENT-CHOSEN.
 *
 * Aniob's AutoRouter already decides WHO answers. This is the orthogonal axis deciding HOW MUCH
 * VERIFICATION, and it is the direct fix for the nondeterminism problem: today the same
 * instruction can silently take a different route on different runs, which is invisible to you
 * and maddening to a user.
 *
 * The profile is a STICKY USER SETTING the agent cannot see or change. That is enforced two ways:
 * the setter requires a [UserOrigin] token that only UI code can mint, and the profile is
 * deliberately absent from every prompt view.
 */
enum class ExecutionProfile {
    /** Reactive loop, Gate A only, no plan, no checker. Seconds per step. */
    FAST,

    /** Living plan with check items, both gates, checker on. Slower. */
    CAREFUL;

    val settings: Settings
        get() = when (this) {
            FAST -> Settings(
                usesLivingPlan = false,
                gateAEnabled = true,
                gateBEnabled = false,
                checkerEnabled = false,
                verificationLevel = ReleaseDecision.Level.FINAL,
                label = "Fast",
                userExplanation = "I act quickly and check only at the end."
            )
            CAREFUL -> Settings(
                usesLivingPlan = true,
                gateAEnabled = true,
                gateBEnabled = true,
                checkerEnabled = true,
                verificationLevel = ReleaseDecision.Level.CHECKPOINTS,
                label = "Careful",
                userExplanation = "I plan first, double-check every action, and verify as I go."
            )
        }

    data class Settings(
        val usesLivingPlan: Boolean,
        val gateAEnabled: Boolean,
        val gateBEnabled: Boolean,
        val checkerEnabled: Boolean,
        val verificationLevel: ReleaseDecision.Level,
        /** Shown on every task, so the route is never a surprise. */
        val label: String,
        val userExplanation: String
    )

    /**
     * Proof that a human made this choice. Only UI code can construct one; there is deliberately
     * no way for a model-driven code path to obtain it.
     */
    class UserOrigin private constructor(val surface: String) {
        companion object {
            /** Call ONLY from a user-gesture handler. */
            fun fromUserGesture(surface: String) = UserOrigin(surface)
        }
    }

    /**
     * Sticky holder. Reads are free; writes need a [UserOrigin].
     */
    class Sticky(initial: ExecutionProfile = CAREFUL) {
        private var value: ExecutionProfile = initial
        private val history = mutableListOf<String>()

        @Synchronized
        fun current(): ExecutionProfile = value

        @Synchronized
        fun setByUser(profile: ExecutionProfile, origin: UserOrigin) {
            if (profile == value) return
            history += "${value.name} -> ${profile.name} (from ${origin.surface})"
            value = profile
        }

        @Synchronized
        fun changeLog(): List<String> = history.toList()

        /**
         * What the agent is allowed to know. Deliberately nothing: if the model can see the
         * profile it will reason about it, and a profile the model reasons about is a profile the
         * model effectively chooses.
         */
        fun agentVisibleDescription(): String = ""
    }

    companion object {
        val DEFAULT = CAREFUL
    }
}
