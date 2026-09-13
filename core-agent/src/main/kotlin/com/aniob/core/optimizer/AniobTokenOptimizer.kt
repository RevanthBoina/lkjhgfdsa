package com.aniob.core.optimizer

import com.aniob.core.domain.AniobNode

/**
 * Prunes raw accessibility nodes and compiles compact, token-optimized representation.
 * Guarantees strictly at most 60 actionable nodes with Set-of-Mark (SoM) tagging.
 */
object AniobTokenOptimizer {

    const val MAX_ACTIONABLE_NODES = 60

    /**
     * Filters, deduplicates, sorts, and indexes actionable nodes.
     */
    fun optimize(rawNodes: List<AniobNode>): List<AniobNode> {
        val filtered = rawNodes.asSequence()
            .filter { it.isVisibleToUser }
            .filter { !it.bounds.isEmpty && it.bounds.area > 50 } // eliminate zero or tiny artifacts
            .filter { isInteractiveOrInformative(it) }
            .distinctBy { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom},${it.text}" }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(MAX_ACTIONABLE_NODES)
            .toList()

        // Re-index nodes with clean Set-of-Mark 1-based sequential indices
        return filtered.mapIndexed { index, node ->
            node.copy(id = index + 1)
        }
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
