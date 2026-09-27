package com.aniob.core.grounding

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.GroundingSource
import com.aniob.core.domain.SemanticTarget

/**
 * Turns a [SemanticTarget] into concrete pixels against a **live** screen capture using the fused element index.
 * Records the resolving perception source (accessibility node, OCR, or visual model).
 */
object AniobGroundingResolver {

    /** A successfully grounded element plus the exact pixels and resolving source. */
    data class Resolved(
        val node: AniobNode,
        val x: Int,
        val y: Int,
        val source: GroundingSource = node.source
    )

    /**
     * Fused resolution priority:
     * 1. SoM index -> node with that id in the fused index
     * 2. resource id -> exact view-id match
     * 3. text / content description -> exact match over fused index, then fuzzy text match
     */
    fun resolve(target: SemanticTarget, liveScreen: AniobScreenState): Resolved? = when (target) {
        is SemanticTarget.SomIndex -> {
            val node = liveScreen.findNodeById(target.index)
            if (node == null || !node.isVisibleToUser || !node.isEnabled) null
            else node.toResolved()
        }

        is SemanticTarget.ResourceId -> {
            val matches = liveScreen.fusedIndex.elements.filter {
                it.isVisibleToUser && it.isEnabled && it.viewId.equals(target.id, ignoreCase = false)
            }
            if (matches.size == 1) matches.first().toResolved()
            else if (matches.size > 1) {
                val clickables = matches.filter { it.isClickable }
                if (clickables.size == 1) clickables.first().toResolved() else null
            } else null
        }

        is SemanticTarget.Text -> resolveText(target, liveScreen)

        is SemanticTarget.ContentDesc -> resolveContentDesc(target, liveScreen)
    }

    private fun resolveText(target: SemanticTarget.Text, screen: AniobScreenState): Resolved? {
        val eligible = screen.fusedIndex.elements.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { it.text.equals(target.text, ignoreCase = true) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }
        if (target.exact) return null

        val fuzzyHits = screen.fusedIndex.fuzzyTextSearch(target.text, minSimilarity = 0.70)
            .filter { it.first.isVisibleToUser && it.first.isEnabled }
        if (fuzzyHits.isNotEmpty()) {
            return fuzzyHits.first().first.toResolved()
        }
        return null
    }

    private fun resolveContentDesc(target: SemanticTarget.ContentDesc, screen: AniobScreenState): Resolved? {
        val eligible = screen.fusedIndex.elements.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { it.contentDescription.equals(target.desc, ignoreCase = true) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }
        if (target.exact) return null

        val partialHits = eligible.filter { it.contentDescription.contains(target.desc, ignoreCase = true) }
        if (partialHits.size == 1) return partialHits.first().toResolved()
        if (partialHits.size > 1) {
            val clickables = partialHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }
        return null
    }

    private fun AniobNode.toResolved(): Resolved = Resolved(node = this, x = centerX, y = centerY, source = source)

    fun resolveContainer(target: SemanticTarget?, liveScreen: AniobScreenState): AniobNode? {
        val scrollables = liveScreen.nodes.filter { it.isScrollable }
        if (scrollables.isEmpty()) return null
        val anchor = target?.let { resolve(it, liveScreen) }?.node ?: return scrollables.maxByOrNull { it.bounds.area }
        return scrollables.firstOrNull { contains(it, anchor) } ?: scrollables.maxByOrNull { it.bounds.area }
    }

    private fun contains(container: AniobNode, child: AniobNode): Boolean =
        container.bounds.left <= child.bounds.left && container.bounds.top <= child.bounds.top &&
            container.bounds.right >= child.bounds.right && container.bounds.bottom >= child.bounds.bottom
}
