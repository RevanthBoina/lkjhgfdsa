package com.aniob.core.skills

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

data class AppMapScreen(
    val treeHash: String,
    val activityName: String?,
    val elements: List<String>,
    val actionsTaken: List<String>
)

data class AppMap(
    val packageName: String,
    val screens: List<AppMapScreen> = emptyList(),
    val totalSteps: Int = 0,
    val exploredAt: Long = System.currentTimeMillis()
)

object SelfExplorer {
    const val MAX_ROUNDS = 8
    const val MAX_STEPS_PER_ROUND = 15

    /**
     * Decides the next exploratory action during a bounded walk.
     * Restraints:
     * - Tap, Swipe, Back ONLY.
     * - Never leaves the target app.
     * - Never confirms anything (skips buttons mentioning confirm, delete, pay, send, etc.).
     */
    fun selectNextExplorationAction(
        targetPackage: String,
        screenState: AniobScreenState,
        visitedHashes: Set<String>,
        stepInRound: Int
    ): AniobAction {
        // If left the app, immediately go back
        if (screenState.packageName != targetPackage) {
            return AniobAction.SystemKey(key = KeyType.BACK, thought = "Return to target app $targetPackage")
        }

        // Filter safe clickable nodes
        val safeNodes = screenState.nodes.filter { node ->
            node.isClickable &&
                !isRiskyElement(node.text, node.contentDescription, node.viewId)
        }

        val targetNode = safeNodes.firstOrNull()

        return if (targetNode != null && stepInRound < MAX_STEPS_PER_ROUND - 2) {
            val target = when {
                !targetNode.viewId.isNullOrBlank() -> SemanticTarget.ResourceId(targetNode.viewId)
                !targetNode.text.isNullOrBlank() -> SemanticTarget.Text(targetNode.text)
                !targetNode.contentDescription.isNullOrBlank() -> SemanticTarget.ContentDesc(targetNode.contentDescription)
                else -> SemanticTarget.SomIndex(targetNode.id)
            }
            AniobAction.Tap(target = target, thought = "Explore element ${targetNode.text ?: targetNode.contentDescription ?: targetNode.viewId}")
        } else if (stepInRound % 5 == 0) {
            AniobAction.Swipe(direction = SwipeDirection.DOWN, thought = "Scroll to discover more elements")
        } else {
            AniobAction.SystemKey(key = KeyType.BACK, thought = "Backtrack to previous screen")
        }
    }

    private fun isRiskyElement(text: String?, desc: String?, id: String?): Boolean {
        val combined = "$text $desc $id".lowercase()
        return combined.contains("delete") ||
            combined.contains("pay") ||
            combined.contains("buy") ||
            combined.contains("purchase") ||
            combined.contains("confirm") ||
            combined.contains("remove") ||
            combined.contains("clear") ||
            combined.contains("reset") ||
            combined.contains("logout") ||
            combined.contains("sign out")
    }
}
