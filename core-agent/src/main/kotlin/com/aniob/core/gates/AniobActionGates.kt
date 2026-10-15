package com.aniob.core.gates

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.perception.AniobFusedIndex
import kotlin.math.hypot
import kotlin.math.max

/**
 * Plan-2 Failure Taxonomy for Pre-Execution Physical Safety Net (Gate B).
 */
enum class GateBFailureCategory {
    TARGET_DISAPPEARED,
    TARGET_SHIFTED,
    TARGET_OCCUPIED,
    PIXEL_TARGET_DISAPPEARED,
    PIXEL_BYPASSED,
    XML_BYPASSED,
    GENERAL
}

/**
 * Plan-2 Taxonomy for Semantic Thought-Action Inconsistency Correction (Gate A).
 */
enum class GateACorrectionTaxonomy {
    SPATIAL_INACCURACY,
    PREMATURE_STEP,
    WRONG_ELEMENT,
    LOGICAL_MISMATCH
}

/**
 * Mandatory human-readable target description for coordinate actions.
 * Marked self-described because it is the agent's intent, not an objective observation.
 */
data class TargetDescription(
    val humanName: String,
    val isSelfDescribed: Boolean = true
) {
    init {
        val trimmed = humanName.trim().lowercase()
        require(trimmed.isNotEmpty() && trimmed != "element" && trimmed != "button") {
            "Target description must be a specific human name (e.g. 'play button', 'search input'), not generic '$humanName'"
        }
    }
}

/**
 * Evaluation record for Gate A (Thought-Action Consistency).
 */
data class GateAResult(
    val isConsistent: Boolean,
    val expectationString: String,
    val correctionNeeded: Boolean = false,
    val errorTaxonomy: GateACorrectionTaxonomy? = null,
    val correctedActionDescription: String? = null
)

/**
 * Evaluation record for Gate B (Pre-Execution Safety Net).
 */
data class GateBResult(
    val allowed: Boolean,
    val failureCategory: GateBFailureCategory? = null,
    val healedTarget: AniobNode? = null,
    val healedX: Int? = null,
    val healedY: Int? = null,
    val reason: String = ""
)

/**
 * Gate B: Physical Pre-Execution Safety Net.
 *
 * DESIGN RULE: be conservative — mistaking a shift for a disappearance is acceptable; mistaking a disappearance for a shift causes real damage.
 */
object AniobPhysicalSafetyNetGate {

    fun evaluate(
        targetNode: AniobNode,
        targetX: Int,
        targetY: Int,
        liveScreen: AniobScreenState,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): GateBResult {
        // Scale reference distance: 100px from 1080x2400 reference
        val scale = hypot(screenWidth.toDouble(), screenHeight.toDouble()) / hypot(1080.0, 2400.0)
        val maxShiftDistance = 100.0 * scale
        val relaxedShiftDistance = 300.0 * scale

        // Check if coordinate is currently occupied by something else (e.g. unexpected popup/dialog)
        val occupantAtCoord = liveScreen.fusedIndex.hitTest(targetX, targetY)
        if (occupantAtCoord != null && occupantAtCoord.id != targetNode.id) {
            val isSameIdentity = occupantAtCoord.viewId.isNotBlank() && occupantAtCoord.viewId == targetNode.viewId
            if (!isSameIdentity && occupantAtCoord.isClickable) {
                return GateBResult(
                    allowed = false,
                    failureCategory = GateBFailureCategory.TARGET_OCCUPIED,
                    reason = "Target coordinates ($targetX, $targetY) occupied by unexpected element: ${occupantAtCoord.className} id='${occupantAtCoord.viewId}'"
                )
            }
        }

        // Score live candidate elements
        var bestCandidate: AniobNode? = null
        var bestScore = -1.0
        var bestIdentityScore = 0.0
        var bestDistance = Double.MAX_VALUE

        for (candidate in liveScreen.fusedIndex.elements) {
            if (!candidate.isVisibleToUser) continue

            // Signal 1: Resource ID (weight 0.5)
            val (idSignal, idScore) = when {
                targetNode.viewId.isNotBlank() && candidate.viewId.isNotBlank() && targetNode.viewId == candidate.viewId -> 1.0 to 1.0
                targetNode.viewId.isNotBlank() && candidate.viewId.isBlank() -> 0.3 to 0.3 // soft
                targetNode.viewId.isNotBlank() && candidate.viewId.isNotBlank() && targetNode.viewId != candidate.viewId -> -0.5 to 0.0 // hard mismatch
                else -> 0.3 to 0.3
            }

            // Signal 2: Text similarity (weight 0.4)
            // Guard against blank-string inflation: two empty strings have no semantic text signal (neutral 0.3), not 1.0.
            val hasTargetText = targetNode.text.isNotBlank() || targetNode.contentDescription.isNotBlank()
            val hasCandText = candidate.text.isNotBlank() || candidate.contentDescription.isNotBlank()
            val textSignal = if (!hasTargetText && !hasCandText) {
                0.3
            } else {
                val textSim = if (targetNode.text.isNotBlank() || candidate.text.isNotBlank()) {
                    AniobFusedIndex.textSimilarity(targetNode.text, candidate.text)
                } else 0.0
                val descSim = if (targetNode.contentDescription.isNotBlank() || candidate.contentDescription.isNotBlank()) {
                    AniobFusedIndex.textSimilarity(targetNode.contentDescription, candidate.contentDescription)
                } else 0.0
                max(textSim, descSim)
            }

            // Signal 3: Bounds overlap ratio (weight 0.3)
            val intersection = targetNode.bounds.intersect(candidate.bounds)
            val unionArea = (targetNode.bounds.area + candidate.bounds.area - intersection.area).coerceAtLeast(1)
            val boundsSignal = intersection.area.toDouble() / unionArea.toDouble()

            // Signal 4: Coordinate containment (weight 0.3)
            val coordSignal = if (candidate.bounds.contains(targetX, targetY)) 1.0 else 0.0

            val weightedSum = (0.5 * idSignal) + (0.4 * textSignal) + (0.3 * boundsSignal) + (0.3 * coordSignal)
            val weightedCount = 0.5 + 0.4 + 0.3 + 0.3
            val score = weightedSum / weightedCount

            val identityScore = ((0.5 * idScore) + (0.4 * textSignal)) / (0.5 + 0.4)

            val candCenterX = candidate.centerX
            val candCenterY = candidate.centerY
            val dist = hypot((candCenterX - targetX).toDouble(), (candCenterY - targetY).toDouble())

            val isBetter = score > bestScore + 1e-6 ||
                (kotlin.math.abs(score - bestScore) <= 1e-6 && dist < bestDistance)
            if (isBetter) {
                bestScore = score
                bestIdentityScore = identityScore
                bestCandidate = candidate
                bestDistance = dist
            }
        }

        if (bestCandidate == null) {
            return GateBResult(
                allowed = false,
                failureCategory = GateBFailureCategory.TARGET_DISAPPEARED,
                reason = "Target element not found on live screen."
            )
        }

        val requiredThreshold = if (bestDistance <= 150.0 * scale) 0.55 else 0.75
        val exactIdMatch = targetNode.viewId.isNotBlank() && bestCandidate.viewId == targetNode.viewId

        val allowableDistance = if (exactIdMatch && (bestIdentityScore >= 0.85 || (bestIdentityScore >= 0.5 && exactIdMatch))) {
            relaxedShiftDistance
        } else {
            maxShiftDistance
        }

        if (bestScore >= requiredThreshold) {
            if (bestDistance <= allowableDistance) {
                // If coordinates shifted within allowable threshold, perform self-healing
                return if (bestDistance > 10.0) {
                    GateBResult(
                        allowed = true,
                        failureCategory = GateBFailureCategory.TARGET_SHIFTED,
                        healedTarget = bestCandidate,
                        healedX = bestCandidate.centerX,
                        healedY = bestCandidate.centerY,
                        reason = "Target coordinates self-healed from ($targetX, $targetY) to (${bestCandidate.centerX}, ${bestCandidate.centerY}) [distance=${bestDistance.toInt()}px]"
                    )
                } else {
                    GateBResult(
                        allowed = true,
                        healedTarget = bestCandidate,
                        healedX = targetX,
                        healedY = targetY,
                        reason = "Target matched with high confidence."
                    )
                }
            } else {
                return GateBResult(
                    allowed = false,
                    failureCategory = GateBFailureCategory.TARGET_SHIFTED,
                    reason = "Target shifted beyond safe threshold (${bestDistance.toInt()}px > ${allowableDistance.toInt()}px)."
                )
            }
        }

        return GateBResult(
            allowed = false,
            failureCategory = GateBFailureCategory.TARGET_DISAPPEARED,
            reason = "Best candidate fell below match threshold ($bestScore < $requiredThreshold)."
        )
    }
}
