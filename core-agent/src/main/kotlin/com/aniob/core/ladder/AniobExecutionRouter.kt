package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteDecision
import com.aniob.core.router.RouteTarget
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.skills.AniobSemanticSkillMatcher
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
    private val skillMatcher: AniobSkillMatcher = AniobSkillMatcher(),
    private val semanticSkillMatcher: AniobSemanticSkillMatcher? = null,
    private val planningAgent: AniobPlanningAgent? = null,
    private val memoryStore: AniobEmbeddingStore? = null
) {
    sealed class ExecutionPlanResult {
        data class DirectIntent(val shortcut: ResolvedIntentShortcut, val reason: String) : ExecutionPlanResult()
        data class FastPathStep(val action: AniobAction, val reason: String) : ExecutionPlanResult()
        data class SkillStepExecution(val action: AniobAction, val skill: AniobSkill, val reason: String) : ExecutionPlanResult()
        data class ModelDispatch(val decision: RouteDecision, val progressSummary: String? = null) : ExecutionPlanResult()
    }

    fun planStep(
        taskPrompt: String,
        screenState: AniobScreenState,
        stepIndex: Int,
        taskSignature: String,
        powerState: DevicePowerState,
        installedModelId: String? = null,
        lastLocalFailCount: Int = 0,
        isModelFileMissing: Boolean = false,
        previousProgress: TaskProgress? = null
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
                // If action targets an element not currently in screenState, try scroll-until-found
                val targetId = when (cachedAction) {
                    is AniobAction.Tap -> cachedAction.targetNodeId
                    is AniobAction.Click -> cachedAction.targetNodeId
                    else -> null
                }
                if (targetId != null && screenState.findNodeById(targetId) == null) {
                    val scrollResult = com.aniob.core.tools.AniobScrollHelper.scrollUntilFound(
                        screenState = screenState,
                        targetPredicate = { it.id == targetId || it.text.contains(taskPrompt, true) },
                        taskPrompt = taskPrompt,
                        capture = { screenState },
                        executeSwipe = { true }
                    )
                    if (scrollResult.found && scrollResult.node != null) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Click(scrollResult.node.id),
                            reason = "Scroll-until-found success after ${scrollResult.swipes} swipes"
                        )
                    }
                }
                return ExecutionPlanResult.FastPathStep(
                    action = cachedAction,
                    reason = "FastPath verified replay step $stepIndex"
                )
            }
        }

        // Step 2: Tier 1.5 YAML or Semantic Skill Match
        val skillMatch = semanticSkillMatcher?.findBestSkill(taskPrompt) ?: skillMatcher.match(taskPrompt)
        if (skillMatch.matched && skillMatch.skill != null) {
            val skill = skillMatch.skill
            val step = skill.steps.getOrNull(stepIndex)
            if (step != null) {
                val action = step.toAniobAction()
                val targetId = when (action) {
                    is AniobAction.Tap -> action.targetNodeId
                    is AniobAction.Click -> action.targetNodeId
                    else -> null
                }
                if (targetId != null && screenState.findNodeById(targetId) == null) {
                    val scrollResult = com.aniob.core.tools.AniobScrollHelper.scrollUntilFound(
                        screenState = screenState,
                        targetPredicate = { it.id == targetId || it.text.contains(taskPrompt, true) },
                        taskPrompt = taskPrompt,
                        capture = { screenState },
                        executeSwipe = { true }
                    )
                    if (scrollResult.found && scrollResult.node != null) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Click(scrollResult.node.id),
                            reason = "Scroll-until-found success after ${scrollResult.swipes} swipes"
                        )
                    }
                }
                return ExecutionPlanResult.SkillStepExecution(
                    action = action,
                    skill = skill,
                    reason = skillMatch.reason
                )
            }
        }

        // Context Retrieval via Memory Store RAG
        var retrievedContext: String? = null
        if (memoryStore != null) {
            val qVec = AniobEmbeddingStore.generatePseudoEmbedding(taskPrompt)
            val memories = memoryStore.search(qVec, topK = 1, threshold = 0.5f)
            if (memories.isNotEmpty()) {
                retrievedContext = memories.first().text
            }
        }

        // Planning Progress condensation: prefrontal-cortex summary replaces the full
        // interleaved observation history in the LLM prompt (~80% token reduction).
        val progress = planningAgent?.updateProgress(
            userInstruction = taskPrompt,
            previousOperation = if (stepIndex > 0) "Step ${stepIndex - 1} executed" else null,
            previousProgress = previousProgress,
            focusContent = retrievedContext
        )

        // Step 3 & 4: AutoRouter decides between Local SLM and Omniroute Cloud
        val decision = AniobAutoRouter.decideRoute(
            taskPrompt = taskPrompt,
            screenState = screenState,
            powerState = powerState,
            hasFastPathHit = false,
            isIntentShortcut = false,
            installedModelId = installedModelId,
            lastLocalFailCount = lastLocalFailCount,
            isModelFileMissing = isModelFileMissing
        )

        return ExecutionPlanResult.ModelDispatch(decision, progressSummary = progress?.summary)
    }
}
