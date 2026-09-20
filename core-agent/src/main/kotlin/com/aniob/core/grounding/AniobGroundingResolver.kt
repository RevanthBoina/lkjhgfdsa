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
        is SemanticTarget.SomIndex ->
            liveScreen.findNodeById(target.index)?.toResolved()

        is SemanticTarget.ResourceId ->
            liveScreen.nodes.firstOrNull { it.viewId.equals(target.id, ignoreCase = false) }?.toResolved()

        is SemanticTarget.Text -> resolveText(target, liveScreen)

        is SemanticTarget.ContentDesc -> resolveContentDesc(target, liveScreen)
    }

    private fun resolveText(target: SemanticTarget.Text, screen: AniobScreenState): Resolved? {
        val nodes = screen.nodes
        val exactHit = nodes.firstOrNull { it.text.equals(target.text, ignoreCase = true) }
        if (exactHit != null) return exactHit.toResolved()
        if (target.exact) return null
        return nodes.firstOrNull { it.text.contains(target.text, ignoreCase = true) }?.toResolved()
    }

    private fun resolveContentDesc(target: SemanticTarget.ContentDesc, screen: AniobScreenState): Resolved? {
        val nodes = screen.nodes
        val exactHit = nodes.firstOrNull { it.contentDescription.equals(target.desc, ignoreCase = true) }
        if (exactHit != null) return exactHit.toResolved()
        if (target.exact) return null
        return nodes.firstOrNull { it.contentDescription.contains(target.desc, ignoreCase = true) }?.toResolved()
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