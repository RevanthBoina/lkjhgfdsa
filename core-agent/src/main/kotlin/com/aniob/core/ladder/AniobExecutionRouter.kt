package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
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
    /** Shared with the learning pipeline so hydration and replay hit the same store. */
    val fastPathEngine: AniobReplayEngine get() = replayEngine

    /** Wires a FastPath lookup callback for use outside the ladder (tracker/learning tests). */
    fun lookup(taskSignature: String): AniobReplayEngine.ReplayTrajectory? =
        replayEngine.findTrajectory(taskSignature)

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
                // If the action names an element not currently on screen, try scroll-until-found
                val target = cachedAction.semanticTarget()
                if (target is SemanticTarget.SomIndex && screenState.nodes.none { it.id == target.index }) {
                    val scrollResult = com.aniob.core.tools.AniobScrollHelper.scrollUntilFound(
                        screenState = screenState,
                        targetPredicate = { it.id == target.index || it.text.contains(taskPrompt, true) },
                        taskPrompt = taskPrompt,
                        capture = { screenState },
                        executeSwipe = { true }
                    )
                    if (scrollResult.found && scrollResult.node != null) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Tap(SemanticTarget.SomIndex(scrollResult.node.id)),
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
                val target = action.semanticTarget()
                if (target is SemanticTarget.SomIndex && screenState.nodes.none { it.id == target.index }) {
                    val scrollResult = com.aniob.core.tools.AniobScrollHelper.scrollUntilFound(
                        screenState = screenState,
                        targetPredicate = { it.id == target.index || it.text.contains(taskPrompt, true) },
                        taskPrompt = taskPrompt,
                        capture = { screenState },
                        executeSwipe = { true }
                    )
                    if (scrollResult.found && scrollResult.node != null) {
                        return ExecutionPlanResult.FastPathStep(
                            action = AniobAction.Tap(SemanticTarget.SomIndex(scrollResult.node.id)),
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
