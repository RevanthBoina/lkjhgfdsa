package com.aniob.core.optimizer

import com.aniob.core.domain.AniobNode

/**
 * Prunes raw accessibility nodes and compiles compact, token-optimized representation.
 * Guarantees strictly at most 60 actionable nodes with Set-of-Mark (SoM) tagging.
 */
object AniobTokenOptimizer {

    const val MAX_ACTIONABLE_NODES = 60

    /**
     * Two-Phase Saliency-Preserving Spatial Foveation:
     * 1. Filters invisible, tiny, and duplicate nodes.
     * 2. If candidates exceed [MAX_ACTIONABLE_NODES], selects the top 60 by interaction saliency
     *    so bottom-of-screen primary actions (FABs, Send/Save buttons, bottom navigation bars)
     *    are never starved by passive text labels above them.
     * 3. Sorts the selected elements in natural reading order (top-to-bottom, left-to-right)
     *    and assigns 1-based Set-of-Mark (SoM) sequential indices [1..60].
     */
    fun optimize(rawNodes: List<AniobNode>): List<AniobNode> {
        val candidates = rawNodes.asSequence()
            .filter { it.isVisibleToUser }
            .filter { !it.bounds.isEmpty && it.bounds.area > 50 } // eliminate zero or tiny artifacts
            .filter { isInteractiveOrInformative(it) }
            .distinctBy { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom},${it.text}" }
            .toList()

        val selected = if (candidates.size <= MAX_ACTIONABLE_NODES) {
            candidates
        } else {
            candidates.sortedWith(
                compareByDescending<AniobNode> { computeSaliencyScore(it) }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            ).take(MAX_ACTIONABLE_NODES)
        }

        val spatiallyOrdered = selected.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        // Re-index nodes with clean Set-of-Mark 1-based sequential indices
        return spatiallyOrdered.mapIndexed { index, node ->
            node.copy(id = index + 1)
        }
    }

    /**
     * Computes an interaction saliency score for foveated node retention when >60 elements exist.
     */
    internal fun computeSaliencyScore(node: AniobNode): Int {
        var score = 0
        if (node.isEditable) score += 50
        if (node.isClickable) score += 40
        if (node.isScrollable) score += 20
        if (node.text.isNotBlank()) score += 15
        if (node.contentDescription.isNotBlank()) score += 15
        if (node.viewId.isNotBlank()) score += 10
        if (node.errorText.isNotBlank()) score += 25
        if (node.isHeading) score += 10
        return score
    }

    private fun isInteractiveOrInformative(node: AniobNode): Boolean {
        // Must either be directly interactable or have descriptive text
        val isInteractable = node.isClickable || node.isEditable || node.isScrollable || node.isSelected
        val hasContent = node.text.isNotBlank() || node.contentDescription.isNotBlank()

        // Exclude pure layout containers that carry no interaction or content
        val isPureLayout = node.className.endsWith("Layout") ||
                node.className.endsWith("ViewGroup") ||
                node.className.endsWith("View")
        if (isPureLayout && !isInteractable && !hasContent) {
            return false
        }

        return isInteractable || hasContent
    }

    /**
     * Compiles the optimized node list into a token-efficient prompt string.
     */
    fun buildOptimizedNodePrompt(nodes: List<AniobNode>): String {
        if (nodes.isEmpty()) {
            return "No actionable elements detected on screen."
        }
        return buildString {
            appendLine("Actionable Screen Elements (Total: ${nodes.size}, Max: $MAX_ACTIONABLE_NODES):")
            nodes.forEach { node ->
                appendLine(node.toOptimizedTokenString())
            }
        }
    }
}
