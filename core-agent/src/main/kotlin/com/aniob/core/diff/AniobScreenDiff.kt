package com.aniob.core.diff

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobFingerprint

/**
 * 4-Level Waterfall Screen Perception & Differential Pipeline.
 *
 * Pin-to-pin comparison:
 * - Worst: adb shell screencap (DroidBot https://github.com/honeynet/droidbot) ~500ms, heavy process fork.
 * - Better: MediaProjection API - requires repetitive runtime user consent dialogues.
 * - Best: AccessibilityService.takeScreenshot() API 30+ (ARTEMIS https://github.com/google/artemis) 30-80ms.
 * - Aniob Improvement: 4-level waterfall:
 *   1. Event-Driven Skip: If no accessibility event occurred since last step, skip screenshot entirely (0ms).
 *   2. Perceptual Tree Hash Cache: If structural node hash is unchanged, reuse cached screenshot state (0ms).
 *   3. Changed Region Crop: If only a bounding sub-region changed, compute diff rect.
 *   4. Downscaled JPEG q80 <=1120px (MobileAgent-Android trick): Downscale bitmap payload by ~85% with zero OCR loss.
 */
object AniobScreenDiff {

    data class WaterfallResult(
        val waterfallLevelUsed: Int, // 1 to 4
        val shouldCaptureFullScreenshot: Boolean,
        val changedRegion: AniobRect?,
        val treeHash: String,
        val isIdenticalToPrevious: Boolean
    )

    /**
     * Executes the differential waterfall between previous screen and current raw node state.
     */
    fun evaluateWaterfall(
        hasAccessibilityContentChanged: Boolean,
        currentNodes: List<AniobNode>,
        previousScreen: AniobScreenState?
    ): WaterfallResult {
        // Level 1: Event-Driven Skip (0ms)
        if (previousScreen != null && !hasAccessibilityContentChanged) {
            return WaterfallResult(
                waterfallLevelUsed = 1,
                shouldCaptureFullScreenshot = false,
                changedRegion = null,
                treeHash = previousScreen.treeHash,
                isIdenticalToPrevious = true
            )
        }

        // Compute new structural tree hash
        val currentFingerprint = computeTreeHash(currentNodes)

        // Level 2: Perceptual Tree Hash Match (0ms capture)
        if (previousScreen != null && previousScreen.treeHash == currentFingerprint) {
            return WaterfallResult(
                waterfallLevelUsed = 2,
                shouldCaptureFullScreenshot = false,
                changedRegion = null,
                treeHash = currentFingerprint,
                isIdenticalToPrevious = true
            )
        }

        // Level 3: Region Crop Calculation
        val changedBounds = computeChangedRegion(previousScreen?.nodes ?: emptyList(), currentNodes)
        if (previousScreen != null && changedBounds != null && changedBounds.area < 500_000) {
            return WaterfallResult(
                waterfallLevelUsed = 3,
                shouldCaptureFullScreenshot = true, // capture and crop
                changedRegion = changedBounds,
                treeHash = currentFingerprint,
                isIdenticalToPrevious = false
            )
        }

        // Level 4: Full Screenshot Downscaled JPEG q80 (max dimension <= 1120px)
        return WaterfallResult(
            waterfallLevelUsed = 4,
            shouldCaptureFullScreenshot = true,
            changedRegion = null,
            treeHash = currentFingerprint,
            isIdenticalToPrevious = false
        )
    }

    private fun computeTreeHash(nodes: List<AniobNode>): String {
        val raw = nodes.take(40).joinToString(";") {
            "${it.id}:${it.className}:${it.text}:${it.bounds.left},${it.bounds.top}"
        }
        return raw.hashCode().toString(16)
    }

    private fun computeChangedRegion(
        oldNodes: List<AniobNode>,
        newNodes: List<AniobNode>
    ): AniobRect? {
        val oldMap = oldNodes.associateBy { it.viewId.ifBlank { "${it.bounds.left},${it.bounds.top}" } }
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        var anyDiff = false

        for (node in newNodes) {
            val key = node.viewId.ifBlank { "${node.bounds.left},${node.bounds.top}" }
            val oldNode = oldMap[key]
            if (oldNode == null || oldNode.text != node.text || oldNode.bounds != node.bounds) {
                anyDiff = true
                minL = minOf(minL, node.bounds.left)
                minT = minOf(minT, node.bounds.top)
                maxR = maxOf(maxR, node.bounds.right)
                maxB = maxOf(maxB, node.bounds.bottom)
            }
        }

        return if (anyDiff && minL < maxR && minT < maxB) {
            AniobRect(minL, minT, maxR, maxB)
        } else null
    }
}
