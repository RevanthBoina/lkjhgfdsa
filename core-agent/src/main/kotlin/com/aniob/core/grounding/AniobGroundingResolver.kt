package com.aniob.core.grounding

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget

/**
 * Turns a [SemanticTarget] into concrete pixels against a **live** screen capture.
 *
 * This is the only place a coordinate is ever produced. It returns `null` on a miss rather than
 * throwing or guessing, so a stale or hallucinated target can never become a blind tap — the
 * caller records a grounding miss and lets the loop recover.
 */
object AniobGroundingResolver {

    /** A successfully grounded element plus the exact pixels to gesture at. */
    data class Resolved(
        val node: AniobNode,
        val x: Int,
        val y: Int
    )

    /**
     * Resolution priority:
     * 1. SoM index → node with that id (already post-optimizer, so it is the model's index)
     * 2. resource id → exact view-id match
     * 3. text / content description → exact match, then contains
     *
     * @return the resolved element and its centre, or `null` when nothing matches.
     */
    fun resolve(target: SemanticTarget, liveScreen: AniobScreenState): Resolved? = when (target) {
        is SemanticTarget.SomIndex -> {
            val node = liveScreen.findNodeById(target.index)
            if (node == null || !node.isVisibleToUser || !node.isEnabled) null
            else node.toResolved()
        }

        is SemanticTarget.ResourceId -> {
            val matches = liveScreen.nodes.filter {
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
        val eligible = screen.nodes.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { it.text.equals(target.text, ignoreCase = true) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null // Reject ambiguous
        }
        if (target.exact) return null
        val partialHits = eligible.filter { it.text.contains(target.text, ignoreCase = true) }
        if (partialHits.size == 1) return partialHits.first().toResolved()
        if (partialHits.size > 1) {
            val clickables = partialHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null // Reject ambiguous
        }
        return null
    }

    private fun resolveContentDesc(target: SemanticTarget.ContentDesc, screen: AniobScreenState): Resolved? {
        val eligible = screen.nodes.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { it.contentDescription.equals(target.desc, ignoreCase = true) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null // Reject ambiguous
        }
        if (target.exact) return null
        val partialHits = eligible.filter { it.contentDescription.contains(target.desc, ignoreCase = true) }
        if (partialHits.size == 1) return partialHits.first().toResolved()
        if (partialHits.size > 1) {
            val clickables = partialHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null // Reject ambiguous
        }
        return null
    }

    private fun AniobNode.toResolved(): Resolved = Resolved(node = this, x = centerX, y = centerY)

    /** Resolves the scrollable container nearest [target], or the largest scrollable node. */
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