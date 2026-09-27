package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import java.util.ArrayDeque

/**
 * Plan-3.3, 3.4, 3.5: Upgraded Watchdog Anti-Loop Guard.
 * Tracks cycle detection (A->B->A->B), perceptual dHash screen delta,
 * planned loop exemptions, and strategic recovery recommendations.
 */
class AniobWatchdog(
    private val loopThreshold: Int = 3
) {
    /**
     * Plan-3.4: Types of loops that are intentional and should be exempted from loop aborts.
     */
    enum class PlannedLoopType {
        PULL_TO_REFRESH,      // Periodic pull-to-refresh with planned intervals
        LIST_INSPECTION,      // "Inspect candidate -> return to list" across distinct items
        CONTINUOUS_SWIPE      // Purposeful continuous swipes through long content
    }

    data class PlannedLoopContext(
        val type: PlannedLoopType,
        val identifier: String? = null
    )

    /**
     * Plan-3.5: Context-aware recovery strategies.
     */
    enum class RecoveryStrategy {
        NONE,
        RE_QUERY,
        PRESS_BACK,
        GO_HOME,
        RELAUNCH_TARGET,
        ASK_MODEL_FOR_PLAN
    }

    data class ActionRound(
        val tool: String,
        val argsFingerprint: String,
        val screenHash: String,
        val dHash: Long?,
        val plannedLoop: PlannedLoopContext?
    )

    private val roundHistory = ArrayDeque<ActionRound>()
    private var consecutiveIneffectiveRounds = 0

    fun record(
        screenHash: String,
        action: AniobAction,
        plannedLoop: PlannedLoopContext? = null,
        dHash: Long? = null
    ) {
        val tool = action.javaClass.simpleName
        val actionKey = action.describeAction()

        // Check screen effectiveness against previous round
        val previous = roundHistory.peekLast()
        if (previous != null) {
            val sameScreen = if (dHash != null && previous.dHash != null) {
                isSameScreenDHash(dHash, previous.dHash)
            } else {
                screenHash == previous.screenHash
            }

            if (sameScreen) {
                consecutiveIneffectiveRounds++
            } else {
                consecutiveIneffectiveRounds = 0
            }
        }

        roundHistory.addLast(
            ActionRound(
                tool = tool,
                argsFingerprint = actionKey,
                screenHash = screenHash,
                dHash = dHash,
                plannedLoop = plannedLoop
            )
        )

        while (roundHistory.size > 20) {
            roundHistory.removeFirst()
        }
    }

    /**
     * Plan-3.3: Detects identical repeats or cycle patterns (A->B->A->B).
     * Honors planned loop exemptions (Plan-3.4).
     */
    fun isLoopDetected(): Boolean {
        if (roundHistory.size < 2) return false

        val recentRounds = roundHistory.toList()

        // Exempt planned loops: if the latest entries are planned loops, do not flag
        val latest = recentRounds.last()
        if (latest.plannedLoop != null) {
            val plannedType = latest.plannedLoop.type
            val recentPlanned = recentRounds.takeLast(loopThreshold).filter { it.plannedLoop?.type == plannedType }
            if (recentPlanned.size >= loopThreshold) {
                return false
            }
        }

        // Check identical repeats
        if (recentRounds.size >= loopThreshold) {
            val window = recentRounds.takeLast(loopThreshold)
            if (window.all { it.argsFingerprint == latest.argsFingerprint }) {
                return true
            }
        }

        return isOscillating()
    }

    /**
     * Plan-3.3: Detects A->B->A->B cycle:
     * fp[n-4] == fp[n-2] && fp[n-3] == fp[n-1] && fp[n-4] != fp[n-3]
     */
    fun isOscillating(): Boolean {
        if (roundHistory.size < 4) return false
        val keys = roundHistory.map { it.argsFingerprint }
        val n = keys.size

        // Direct check for A->B->A->B
        val a1 = keys[n - 4]
        val b1 = keys[n - 3]
        val a2 = keys[n - 2]
        val b2 = keys[n - 1]

        if (a1 == a2 && b1 == b2 && a1 != b1) {
            // Check if this cycle is an exempted list inspection (e.g. tap item -> press back -> tap next)
            val rounds = roundHistory.toList()
            val hasExemption = rounds.takeLast(4).any { it.plannedLoop?.type == PlannedLoopType.LIST_INSPECTION }
            if (!hasExemption) {
                return true
            }
        }

        // Subsequence oscillation for cycle length 2 or 3
        for (cycleLen in 2..3) {
            val windowSize = cycleLen * 2
            if (keys.size >= windowSize) {
                val window = keys.takeLast(windowSize)
                val firstHalf = window.take(cycleLen)
                val secondHalf = window.takeLast(cycleLen)
                if (firstHalf == secondHalf) {
                    return true
                }
            }
        }
        return false
    }

    fun getConsecutiveIneffectiveRounds(): Int = consecutiveIneffectiveRounds

    /**
     * Plan-3.5: Recommends a recovery strategy based on current runtime context.
     */
    fun determineRecoveryStrategy(
        isOverlayPresent: Boolean,
        isExpectedAppForeground: Boolean
    ): RecoveryStrategy {
        return when {
            isOverlayPresent -> RecoveryStrategy.PRESS_BACK
            !isExpectedAppForeground -> RecoveryStrategy.RELAUNCH_TARGET
            else -> RecoveryStrategy.ASK_MODEL_FOR_PLAN
        }
    }

    /**
     * Plan-3.5: Generates strategic warning hint to feed directly back into model prompt.
     */
    fun generateRecoveryHint(actionA: String, actionB: String): String {
        return "[System Warning] You are in an action cycle ($actionA <-> $actionB). Stop repeating these actions and try a fundamentally different approach, or finish if the task cannot be completed."
    }

    fun reset() {
        roundHistory.clear()
        consecutiveIneffectiveRounds = 0
    }

    companion object {
        /**
         * Perceptual hash (dHash) comparison.
         * Calibration from 460 on-device steps:
         * same screen <= 4 Hamming distance, different screens >= 7. Threshold: 5.
         */
        const val DHASH_THRESHOLD = 5

        fun hammingDistance(hash1: Long, hash2: Long): Int {
            return java.lang.Long.bitCount(hash1 xor hash2)
        }

        fun isSameScreenDHash(hash1: Long, hash2: Long): Boolean {
            return hammingDistance(hash1, hash2) <= DHASH_THRESHOLD
        }
    }
}
