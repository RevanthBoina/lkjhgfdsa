package com.aniob.app.provider

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget

/**
 * Deterministic Mock Provider for automated regression testing and offline simulation.
 */
class AniobMockProvider {

    fun planNextStep(taskPrompt: String, stepIndex: Int, screenState: AniobScreenState): AniobAction {
        val lower = taskPrompt.lowercase()

        // Handle settings navigation
        if (lower.contains("settings")) {
            return if (stepIndex == 0) {
                val node = screenState.findNodeByText("Settings") ?: screenState.nodes.firstOrNull { it.isClickable }
                if (node != null) AniobAction.Tap(SemanticTarget.SomIndex(node.id), thought = "Clicking Settings")
                else AniobAction.Finish(summary = "Settings already opened", thought = "Done")
            } else {
                AniobAction.Finish(summary = "Settings navigation complete", thought = "Done")
            }
        }

        // Handle cab booking simulated steps
        if (lower.contains("cab") || lower.contains("ride")) {
            return when (stepIndex) {
                0 -> {
                    val searchNode = screenState.findNodeByText("Where to") ?: screenState.findNodeByText("Search") ?: screenState.nodes.firstOrNull { it.isEditable }
                    if (searchNode != null) {
                        AniobAction.InputText(SemanticTarget.SomIndex(searchNode.id), text = "Office", thought = "Entering destination Office")
                    } else {
                        AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "Selecting destination input")
                    }
                }
                1 -> {
                    val rideNode = screenState.findNodeByText("Uber")
                        ?: screenState.findNodeByText("Standard")
                        ?: screenState.nodes.firstOrNull { it.isClickable }
                    if (rideNode != null) {
                        AniobAction.Tap(SemanticTarget.SomIndex(rideNode.id), thought = "Selecting ride tier")
                    } else {
                        AniobAction.Fail(reason = "No ride tier option visible", thought = "No selectable ride tier")
                    }
                }
                else -> AniobAction.Finish(summary = "Cab booking route confirmed", thought = "Done")
            }
        }

        // General fallback
        val targetNode = screenState.nodes.firstOrNull { it.isClickable }
        return if (targetNode != null && stepIndex < 2) {
            AniobAction.Tap(SemanticTarget.SomIndex(targetNode.id), thought = "Progressing toward goal")
        } else {
            AniobAction.Finish(summary = "Goal satisfied", thought = "Completed")
        }
    }
}
