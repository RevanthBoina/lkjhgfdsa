package com.aniob.core.domain

/**
 * Semantic element target — the ONLY way an LLM-proposed action can name an element.
 *
 * Coordinates are deliberately unrepresentable here: a model cannot emit pixels, so a
 * hallucinated tap is impossible by construction. Pixels are produced exclusively by
 * `AniobGroundingResolver` from the live accessibility tree.
 */
sealed interface SemanticTarget {
    /** Set-of-Mark index from the optimized node list ([1..60]). */
    data class SomIndex(val index: Int) : SemanticTarget

    /** Android view id, e.g. `com.android.settings:id/search_action_bar`. */
    data class ResourceId(val id: String) : SemanticTarget

    /** Visible text; [exact] requires a full match, otherwise contains. */
    data class Text(val text: String, val exact: Boolean = false) : SemanticTarget

    /** Content description (accessibility label); [exact] requires a full match. */
    data class ContentDesc(val desc: String, val exact: Boolean = false) : SemanticTarget

    /** Coordinate-based fallback for Canvas-rendered, Flutter/Unity, or vision grounding ([0..1000]). */
    data class NormalizedCoordinate(val normX: Int, val normY: Int) : SemanticTarget

    /** Bounding-box fallback for Canvas/vision grounding ([left, top, right, bottom] in [0..1000]). */
    data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int) : SemanticTarget

    /** Human-readable form for logs, prompts and watchdogs. */
    fun describe(): String = when (this) {
        is SomIndex -> "som:$index"
        is ResourceId -> "id:$id"
        is Text -> "text:'$text'${if (exact) " (exact)" else ""}"
        is ContentDesc -> "desc:'$desc'${if (exact) " (exact)" else ""}"
        is NormalizedCoordinate -> "coord:($normX,$normY)"
        is BoundingBox -> "box:[$left,$top,$right,$bottom]"
    }
}

/**
 * True when [node] satisfies this target. Matching rules per kind:
 * - [SemanticTarget.SomIndex]  → the node's (re-indexed) id equals the index
 * - [SemanticTarget.ResourceId]→ exact view-id match
 * - [SemanticTarget.Text]      → exact match when requested, else case-insensitive contains
 * - [SemanticTarget.ContentDesc]→ same, over contentDescription
 * - [SemanticTarget.NormalizedCoordinate] → node bounds contains normalized point
 * - [SemanticTarget.BoundingBox] → node bounds overlaps target box
 */
fun SemanticTarget.matches(node: AniobNode): Boolean = when (this) {
    is SemanticTarget.SomIndex -> node.id == index
    is SemanticTarget.ResourceId -> node.viewId.equals(id, ignoreCase = false)
    is SemanticTarget.Text ->
        if (exact) node.text.equals(text, ignoreCase = true)
        else node.text.contains(text, ignoreCase = true)
    is SemanticTarget.ContentDesc ->
        if (exact) node.contentDescription.equals(desc, ignoreCase = true)
        else node.contentDescription.contains(desc, ignoreCase = true)
    is SemanticTarget.NormalizedCoordinate ->
        node.bounds.left <= normX && normX <= node.bounds.right &&
            node.bounds.top <= normY && normY <= node.bounds.bottom
    is SemanticTarget.BoundingBox ->
        !(node.bounds.right < left || node.bounds.left > right ||
            node.bounds.bottom < top || node.bounds.top > bottom)
}