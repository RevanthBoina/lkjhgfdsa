package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteDecision
import com.aniob.core.router.RouteTarget
import com.aniob.core.skills.AniobSkill
import com.aniob.core.skills.AniobSkillMatcher
import com.aniob.core.tools.AniobReplayEngine
import com.aniob.core.tools.DevicePowerState

/**
 * Execution Ladder Coordinator.
 * Sequentially queries:
 * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
 */
class AniobExecutionRouter(
    private val replayEngine: AniobReplayEngine = AniobReplayEngine(),
    private val skillMatcher: AniobSkillMatcher = AniobSkillMatcher()
) {
    sealed class ExecutionPlanResult {
        data class DirectIntent(val shortcut: ResolvedIntentShortcut, val reason: String) : ExecutionPlanResult()
        data class FastPathStep(val action: AniobAction, val reason: String) : ExecutionPlanResult()
        data class SkillStepExecution(val action: AniobAction, val skill: AniobSkill, val reason: String) : ExecutionPlanResult()
        data class ModelDispatch(val decision: RouteDecision) : ExecutionPlanResult()
    }

    fun planStep(
        taskPrompt: String,
        screenState: AniobScreenState,
        stepIndex: Int,
        taskSignature: String,
        powerState: DevicePowerState
    ): ExecutionPlanResult {
        // Step 0: Direct Intent Shortcut (0ms LLM)
        if (stepIndex == 0) {
            val intentShortcut = AniobIntentResolver.resolve(taskPrompt)
            if (intentShortcut != null) {
                return ExecutionPlanResult.DirectIntent(
                    shortcut = intentShortcut,
                    reason = "Direct intent shortcut resolved in <15ms"
                )
            }
        }

        // Step 1: FastPath trajectory replay with fingerprint verification (0ms LLM, 0 tokens)
        val trajectory = replayEngine.findTrajectory(taskSignature)
        if (trajectory != null) {
            val cachedAction = replayEngine.nextAction(trajectory, stepIndex, screenState)
            if (cachedAction != null) {
                return ExecutionPlanResult.FastPathStep(
                    action = cachedAction,
                    reason = "FastPath verified replay step $stepIndex"
                )
            }
        }

        // Step 2: Tier 1.5 YAML Skill Match (0 LLM calls if declarative skill exists)
        val skillMatch = skillMatcher.match(taskPrompt)
        if (skillMatch.matched && skillMatch.skill != null) {
            val skill = skillMatch.skill
            val step = skill.steps.getOrNull(stepIndex)
            if (step != null) {
                return ExecutionPlanResult.SkillStepExecution(
                    action = step.toAniobAction(),
                    skill = skill,
                    reason = skillMatch.reason
                )
            }
        }

        // Step 3 & 4: AutoRouter decides between Local SLM (LiteRT) and Omniroute Cloud
        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = taskPrompt,
            screenState = screenState,
            powerState = powerState,
            hasFastPathHit = false,
            isIntentShortcut = false
        )

        return ExecutionPlanResult.ModelDispatch(decision)
    }
}
