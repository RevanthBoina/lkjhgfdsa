package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState

/**
 * High-speed deterministic action verifier (<10ms, ZERO LLM calls).
 */
object DeterministicVerifier {

    data class VerificationResult(
        val isSuccessful: Boolean,
        val durationMs: Long,
        val explanation: String
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

        // If this was a terminal action, it is automatically successful
        if (action is AniobAction.Finish) {
            return VerificationResult(true, System.currentTimeMillis() - start, "Task declared finished.")
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
                    true to "Tap dispatched at (${action.x}, ${action.y})."
                }
            }

            is AniobAction.Click -> {
                val targetBefore = screenBefore.findNodeById(action.targetNodeId)
                val targetAfter = screenAfter.findNodeById(action.targetNodeId)
                // Success if screen changed or target node changed state or disappeared
                val screenChanged = screenBefore.treeHash != screenAfter.treeHash
                val stateChanged = targetBefore != null && (targetAfter == null || targetAfter.text != targetBefore.text || targetAfter.isSelected != targetBefore.isSelected)
                if (screenChanged || stateChanged) {
                    true to "Click confirmed: UI state transitioned."
                } else {
                    false to "Click had no observable effect on UI."
                }
            }

            is AniobAction.LongPress -> {
                if (screenBefore.treeHash != screenAfter.treeHash) {
                    true to "LongPress confirmed: UI state transitioned."
                } else {
                    true to "LongPress dispatched for ${action.durationMs}ms."
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
                val targetAfter = screenAfter.findNodeById(action.targetNodeId)
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
                true to "Wait duration elapsed."
            }

            is AniobAction.ConfirmWithUser -> {
                true to "Confirmation gate handled."
            }

            else -> {
                true to "Action ${action.toolName} verified."
            }
        }

        val duration = System.currentTimeMillis() - start
        return VerificationResult(result.first, duration, result.second)
    }
}
