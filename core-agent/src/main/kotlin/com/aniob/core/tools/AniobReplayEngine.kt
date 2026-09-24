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

    fun removeTrajectory(taskSignature: String): ReplayTrajectory? {
        return fastPathRegistry.remove(taskSignature)
    }

    fun clear() {
        fastPathRegistry.clear()
    }

    /**
     * Attempts to resolve the next action from cache.
     * Verifies screen fingerprint to ensure UI has not diverged.
     * Never bypasses fingerprint verification even on step 0, and validates package name match.
     */
    fun nextAction(
        trajectory: ReplayTrajectory,
        stepIndex: Int,
        currentScreen: AniobScreenState
    ): AniobAction? {
        if (stepIndex >= trajectory.steps.size) return null
        if (trajectory.packageName.isNotBlank() && currentScreen.packageName.isNotBlank() &&
            currentScreen.packageName != trajectory.packageName
        ) {
            return null
        }
        val step = trajectory.steps[stepIndex]
        val currentFingerprint = AniobFingerprint.computeScreenFingerprint(currentScreen)

        // Validate expected fingerprint matches on all steps (including step 0)
        return if (step.expectedFingerprint.isNotBlank() && step.expectedFingerprint == currentFingerprint) {
            step.action
        } else {
            // Divergence detected - fallback to router
            null
        }
    }
}
