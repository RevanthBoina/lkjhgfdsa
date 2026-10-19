package com.aniob.core.perception

import com.aniob.core.domain.AniobNode

/**
 * Decides when an observation needs an actual bitmap in addition to the node tree.
 *
 * The bitmap is the expensive part of the observe step (IPCs into SurfaceFlinger plus a JPEG
 * encode), so it is only requested when the tree cannot carry the step by itself:
 *
 * - baseline observation always captures — that is the measured cost of the raw condition;
 * - the compact conditions skip it when the pruned tree already exposes at least
 *   [MIN_LABELED_INTERACTIVE_NODES] labelled interactive elements and the previous step did not
 *   fail (nothing new to look at, nothing to diagnose);
 * - a sparse tree (custom canvas, WebView, game surface) and a retry after a failed verify always
 *   capture, because the tree does not describe what changed.
 *
 * Pure JVM so the decision table is unit-testable on its own.
 */
object ObserveCapturePolicy {

    /** Below this many labelled, interactive elements the tree is considered sparse. */
    const val MIN_LABELED_INTERACTIVE_NODES = 3

    /**
     * @param fastObservation true for the compact observe conditions; false for baseline.
     * @param labeledInteractiveNodes result of [labeledInteractiveCount] on the pruned screen.
     * @param previousStepFailed the last verify did not hold (retry / diagnosis needs pixels).
     * @param visionRequested the task itself asks for something visual (look, read, image).
     */
    fun shouldCaptureScreenshot(
        fastObservation: Boolean,
        labeledInteractiveNodes: Int,
        previousStepFailed: Boolean,
        visionRequested: Boolean = false
    ): Boolean {
        if (!fastObservation) return true
        if (visionRequested) return true
        return previousStepFailed || labeledInteractiveNodes < MIN_LABELED_INTERACTIVE_NODES
    }

    /** Counts nodes that are both actionable and named — the signal the compact tree is enough. */
    fun labeledInteractiveCount(nodes: Iterable<AniobNode>): Int = nodes.count { node ->
        val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isChecked
        interactive && (node.text.isNotBlank() || node.contentDescription.isNotBlank())
    }
}
