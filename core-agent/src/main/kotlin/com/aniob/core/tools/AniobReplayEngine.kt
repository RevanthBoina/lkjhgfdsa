package com.aniob.core.tools

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState

/**
 * FastPath Replay Engine: replays cached deterministic trajectories with fingerprint safety checks.
 */
class AniobReplayEngine {

    data class ReplayTrajectory(
        val taskSignature: String,
        val packageName: String,
        val steps: List<ReplayStep>
    )

    data class ReplayStep(
        val expectedFingerprint: String,
        val action: AniobAction
    )

    private val fastPathRegistry = mutableMapOf<String, ReplayTrajectory>()

    fun registerTrajectory(trajectory: ReplayTrajectory) {
        fastPathRegistry[trajectory.taskSignature] = trajectory
    }

    fun findTrajectory(taskSignature: String): ReplayTrajectory? {
        return fastPathRegistry[taskSignature]
    }

    /**
     * Attempts to resolve the next action from cache.
     * Verifies screen fingerprint to ensure UI has not diverged.
     */
    fun nextAction(
        trajectory: ReplayTrajectory,
        stepIndex: Int,
        currentScreen: AniobScreenState
    ): AniobAction? {
        if (stepIndex >= trajectory.steps.size) return null
        val step = trajectory.steps[stepIndex]
        val currentFingerprint = AniobFingerprint.computeScreenFingerprint(currentScreen)

        // If fingerprint matches or is first step, accept cached action
        return if (stepIndex == 0 || step.expectedFingerprint == currentFingerprint) {
            step.action
        } else {
            // Divergence detected - fallback to router
            null
        }
    }
}
