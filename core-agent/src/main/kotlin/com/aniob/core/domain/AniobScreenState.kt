package com.aniob.core.domain

import com.aniob.core.perception.AniobFusedIndex
import com.aniob.core.perception.AniobPerceptionTier

/**
 * Screen snapshot capturing actionable nodes, fused element index, and structural hash for perception.
 */
data class AniobScreenState(
    val packageName: String,
    val activityName: String = "",
    val nodes: List<AniobNode> = emptyList(),
    val fusedIndex: AniobFusedIndex = AniobFusedIndex(nodes),
    val perceptionTier: AniobPerceptionTier = AniobPerceptionTier.FAST,
    val treeHash: String = "",
    val screenshotBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val changedRegion: AniobRect? = null,
    val isEventDrivenSkip: Boolean = false,
    /**
     * `packageName:activityName:structuralSignature.hashCode()` for the *visible interactive*
     * structure of this screen. Empty on captures that did not run the compact pipeline.
     */
    val screenHash: String = ""
) {
    fun findNodeById(id: Int): AniobNode? = fusedIndex.findById(id) ?: nodes.find { it.id == id }

    fun findNodeByText(textQuery: String, ignoreCase: Boolean = true): AniobNode? =
        fusedIndex.exactMatch(textQuery, ignoreCase).firstOrNull() ?:
        fusedIndex.fuzzyTextSearch(textQuery).firstOrNull()?.first ?:
        nodes.find {
            it.text.contains(textQuery, ignoreCase) ||
            it.contentDescription.contains(textQuery, ignoreCase)
        }

    val actionableNodeCount: Int get() = nodes.size
}
