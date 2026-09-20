package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.matches

/**
 * High-speed deterministic action verifier (<10ms, ZERO LLM calls).
 */
object DeterministicVerifier {

    data class VerificationResult(
        val isExpected: Boolean,
        val durationMs: Long,
        val reason: String,
        /** Backwards-compatible aliases (AIM pins use isExpected/reason as primary fields). */
        val isSuccessful: Boolean = isExpected,
        val explanation: String = reason
    )

    /**
     * Verifies that the previous action caused an expected state transition.
     */
    fun verify(
        action: AniobAction,
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState
    ): VerificationResult {
        val start = System.currentTimeMillis()

        // A Finish is PROVISIONAL — it carries no evidence, so it is never auto-successed.
        // The pipeline must call verifyFinish() with the task's SuccessCriteria before it may
        // report SUCCESS. Fail, however, is a genuine terminal failure.
        if (action is AniobAction.Finish) {
            return VerificationResult(false, System.currentTimeMillis() - start, "Finish is provisional: no evidence")
        }
        if (action is AniobAction.Fail) {
            return VerificationResult(false, System.currentTimeMillis() - start, action.reason)
        }

        if (screenBefore == null) {
            return VerificationResult(true, System.currentTimeMillis() - start, "Initial step verified.")
        }

        val result = when (action) {
            is AniobAction.Tap -> {
                if (screenBefore.treeHash != screenAfter.treeHash || screenBefore.packageName != screenAfter.packageName) {
                    true to "Tap confirmed: screen transitioned."
                } else {
                    false to "No-effect: treeHash unchanged after Tap on ${action.describeTarget()}."
                }
            }

            is AniobAction.LongPress -> {
                if (screenBefore.treeHash != screenAfter.treeHash) {
                    true to "LongPress confirmed: UI state transitioned."
                } else {
                    false to "No-effect: treeHash unchanged after LongPress for ${action.durationMs}ms."
                }
            }

            is AniobAction.OpenApp -> {
                if (screenAfter.packageName.contains(action.packageName, ignoreCase = true) || screenBefore.packageName != screenAfter.packageName) {
                    true to "OpenApp confirmed: target package foregrounded."
                } else {
                    false to "OpenApp failed to bring target package to foreground."
                }
            }

            is AniobAction.InputText -> {
                val targetAfter = screenAfter.nodes.firstOrNull { action.target.matches(it) }
                if (targetAfter != null && targetAfter.text.contains(action.text, ignoreCase = true)) {
                    true to "InputText confirmed: text present in target node."
                } else if (screenBefore.treeHash != screenAfter.treeHash) {
                    true to "InputText confirmed: screen updated after input."
                } else {
                    false to "InputText failed: text not found in target."
                }
            }

            is AniobAction.Swipe -> {
                if (screenBefore.treeHash != screenAfter.treeHash) {
                    true to "Swipe confirmed: view content shifted."
                } else {
                    false to "Swipe had no effect (reached list boundary or gesture unrecognized)."
                }
            }

            is AniobAction.SystemKey -> {
                if (screenBefore.treeHash != screenAfter.treeHash || screenBefore.packageName != screenAfter.packageName) {
                    true to "System key press confirmed: screen or package changed."
                } else {
                    false to "System key press had no effect."
                }
            }

            is AniobAction.PressKey -> {
                if (screenBefore.treeHash != screenAfter.treeHash || screenBefore.packageName != screenAfter.packageName) {
                    true to "Key press confirmed: screen or package changed."
                } else {
                    false to "Key press had no effect."
                }
            }

            is AniobAction.Wait -> {
                if (screenBefore.treeHash != screenAfter.treeHash) {
                    true to "Wait confirmed: UI settled to a new state."
                } else {
                    false to "No-effect: treeHash unchanged after Wait for ${action.durationMs}ms."
                }
            }

            is AniobAction.ConfirmWithUser -> {
                true to "Confirmation gate handled."
            }

            else -> {
                true to "Action ${action.toolName} verified."
            }
        }

        val duration = System.currentTimeMillis() - start
        return VerificationResult(isExpected = result.first, durationMs = duration, reason = result.second)
    }

    /**
     * Decides whether a provisional [AniobAction.Finish] may become SUCCESS.
     *
     * This is the evidence gate: the task's [SuccessCriteria] must be satisfied against the final
     * screen before the loop is allowed to declare victory. An empty criteria set carries no
     * evidence, so it is reported as unverified rather than silently accepted — the reflector
     * then decides whether to accept it.
     */
    fun verifyFinish(
        criteria: SuccessCriteria?,
        finalScreen: AniobScreenState,
        steps: Int
    ): VerificationResult {
        val start = System.currentTimeMillis()
        if (criteria == null || criteria.isEmpty) {
            return VerificationResult(
                false,
                System.currentTimeMillis() - start,
                "No success criteria to verify against"
            )
        }
        val unmet = criteria.unmet(finalScreen, steps)
        val duration = System.currentTimeMillis() - start
        return if (unmet.isEmpty()) {
            VerificationResult(true, duration, "Finish evidence satisfied: all criteria met")
        } else {
            VerificationResult(false, duration, "Unmet criteria: ${unmet.joinToString("; ")}")
        }
    }
}
