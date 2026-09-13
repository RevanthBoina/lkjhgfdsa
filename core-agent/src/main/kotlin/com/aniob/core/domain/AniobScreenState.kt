package com.aniob.core.domain

/**
 * Screen snapshot capturing actionable nodes and structural hash for perception.
 */
data class AniobScreenState(
    val packageName: String,
    val activityName: String = "",
    val nodes: List<AniobNode> = emptyList(),
    val treeHash: String = "",
    val screenshotBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val changedRegion: AniobRect? = null,
    val isEventDrivenSkip: Boolean = false
) {
    fun findNodeById(id: Int): AniobNode? = nodes.find { it.id == id }

    fun findNodeByText(textQuery: String, ignoreCase: Boolean = true): AniobNode? =
        nodes.find {
            it.text.contains(textQuery, ignoreCase) ||
            it.contentDescription.contains(textQuery, ignoreCase)
        }

    val actionableNodeCount: Int get() = nodes.size
}
