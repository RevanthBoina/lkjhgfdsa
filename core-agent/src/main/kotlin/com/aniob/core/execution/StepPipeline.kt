package com.aniob.core.execution

import com.aniob.core.config.AgentLimits
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext
import com.aniob.core.learning.LearningPipeline
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The ONE step loop every ladder rung shares (finding #1).
 *
 * `executeTaskPipeline` used to be a ~500-line God-function with four near-identical branches
 * (DirectIntent / FastPath / Skill / Model), each privately duplicating safety -> dispatch ->
 * sleep -> capture -> verify -> reflect -> record. Any upgrade applied per-branch drifted four
 * ways within weeks.
 *
 * [executeStep] runs the full ordered pipeline once:
 *
 * ```
 * OBSERVE -> PROPOSE -> GATE -> DISPATCH -> SETTLE -> VERIFY -> REFLECT -> RECORD
 * ```
 *
 * Rungs differ ONLY in [Proposal]; everything downstream is shared. All Android and LLM touch
 * points are injected as lambdas, so the whole loop is JVM-testable without a device.
 */
class StepPipeline(
    private val limits: AgentLimits = AgentLimits.DEFAULT,
    private val learning: LearningPipeline = LearningPipeline.NoOp,
    private val reflectionAgent: AniobReflectionAgent = AniobReflectionAgent(),
    /** Fan-out for the unified recording contract (execution tracker + hippocampus + event log). */
    private val recordStep: (StepRecord) -> Unit = {},
    /** Settle seam: pause for quiescence after the action and return a fresh screen. */
    private val settle: suspend (AniobAction, suspend () -> AniobScreenState) -> AniobScreenState = { _, capture -> capture() }
) {

    /** A proposed next action plus the rung that produced it. Rungs differ only here. */
    sealed interface Proposal {
        val action: AniobAction
        val providerLabel: String

        data class DirectIntent(override val action: AniobAction, val label: String = "INTENT") : Proposal {
            override val providerLabel: String get() = label
        }

        data class FastPath(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "FASTPATH"
        }

        data class Skill(override val action: AniobAction, val skillName: String = "") : Proposal {
            override val providerLabel: String = "SKILL"
        }

        data class Model(override val action: AniobAction, val provider: String) : Proposal {
            override val providerLabel: String get() = provider
        }

        data class WatchdogBack(override val action: AniobAction = AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK)) : Proposal {
            override val providerLabel: String = "WATCHDOG"
        }

        data class ScrollRetry(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "SCROLL_RETRY"
        }

        data class ReflectionRemedial(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "REFLECTION"
        }

        data class SessionRecovery(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "SESSION_RECOVERY"
        }
    }

    /** What actually happened when the action was dispatched. */
    data class DispatchOutcome(
        val dispatched: Boolean,
        val reason: String = "",
        val screenAfter: AniobScreenState? = null
    )

    /** One recorded step, fanned out through [recordStep]. */
    data class StepRecord(
        val stepIndex: Int,
        val action: AniobAction,
        val provider: String,
        val dispatched: Boolean,
        val verified: Boolean,
        val failureReason: String?,
        val resolvedNode: AniobNode?,
        val latencyMs: Long,
        val tokensUsed: Int
    )

    suspend fun executeStep(
        ctx: TaskContext,
        planningScreen: AniobScreenState,
        proposal: Proposal,
        capture: suspend () -> AniobScreenState,
        dispatch: suspend (AniobAction) -> DispatchOutcome,
        confirm: suspend (String) -> Boolean = { false },
        judge: (String) -> ReflectionOutcome = { ReflectionOutcome.rejected(it, it) },
        targetNode: AniobNode? = null
    ): StepResult {
        currentCoroutineContext().ensureActive()
        val start = System.currentTimeMillis()
        val action = proposal.action

        // OBSERVE: always a fresh capture. Failed capture must not silently reuse stale planning data!
        val observed = runCatching { capture() }.getOrElse {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, planningScreen, "capture failed: ${it.message}").withFailure()
            return finish(
                action, proposal, nextCtx, planningScreen, null,
                StepResult.TerminalState.FAIL, "capture failed: ${it.message}",
                start, verification = null, dispatched = false
            )
        }

        currentCoroutineContext().ensureActive()

        // GATE: safety sees the *resolved node*, not a substring of toString()
        val resolvedNode = targetNode ?: AniobActionExecutor.resolveTarget(action, observed)?.node
        val intercept = AniobSafetyInterceptor.evaluateAction(
            action = action,
            targetNode = resolvedNode,
            screenState = observed,
            screenFingerprint = observed.treeHash
        )
        if (!intercept.isAllowed) {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, "blocked: ${intercept.reason}")
                .withFailure()
            return finish(
                action, proposal, nextCtx, observed, resolvedNode,
                StepResult.TerminalState.FAIL, intercept.reason, start,
                verification = null, dispatched = false
            )
        }

        if (intercept.requiresConfirmation) {
            currentCoroutineContext().ensureActive()
            val approved = confirm(action.describeAction())
            currentCoroutineContext().ensureActive()
            if (!approved) {
                val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, "declined: ${action.describeAction()}")
                    .withFailure()
                return finish(
                    action, proposal, nextCtx, observed, resolvedNode,
                    StepResult.TerminalState.FAIL, "User declined confirmation", start,
                    verification = null, dispatched = false
                )
            }
        }

        // DISPATCH: grounding is redone against the live tree inside the seam; a miss is never a tap.
        currentCoroutineContext().ensureActive()
        val outcome = runCatching { dispatch(action) }
            .getOrElse { DispatchOutcome(dispatched = false, reason = "dispatch threw: ${it.message}") }

        val liveCommand = AniobActionExecutor.planDispatch(action, observed)

        // DispatchOutcome.dispatched=false must prevent a verified action
        if (!outcome.dispatched) {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, "dispatch failed: ${outcome.reason}").withFailure()
            return finish(
                action, proposal, nextCtx, observed, resolvedNode,
                StepResult.TerminalState.FAIL, outcome.reason.ifBlank { "Action not dispatched" },
                start, verification = null, dispatch = liveCommand, dispatched = false
            )
        }

        currentCoroutineContext().ensureActive()

        // SETTLE: idle-gate instead of a fixed sleep
        val screenAfter = outcome.screenAfter ?: runCatching { settle(action, capture) }.getOrDefault(observed)

        currentCoroutineContext().ensureActive()

        // VERIFY
        val verification = if (action is AniobAction.Finish) {
            DeterministicVerifier.verifyFinish(ctx.successCriteria, screenAfter, ctx.stepIndex + 1)
        } else {
            DeterministicVerifier.verify(action, observed, screenAfter)
        }

        // REFLECT
        var nextCtx = ctx.withStep(ctx.stepIndex + 1, screenAfter, historyEntry(action, verification, proposal))
            .withAction(action)
        var reflection: ReflectionOutcome? = null
        var terminal = StepResult.TerminalState.CONTINUE

        if (action is AniobAction.Fail) {
            terminal = StepResult.TerminalState.FAIL
        } else if (action is AniobAction.Finish) {
            // Finish is PROVISIONAL: it only becomes SUCCESS with evidence
            val verdict = if (verification.isExpected) {
                ReflectionOutcome.confirmed(verification.reason)
            } else {
                reflectionAgent.reflectFinish(
                    criteria = ctx.successCriteria,
                    finalScreen = screenAfter,
                    steps = ctx.stepIndex + 1,
                    deterministic = verification,
                    llmJudge = judge
                )
            }
            reflection = verdict
            if (verdict.confirmed) {
                terminal = StepResult.TerminalState.SUCCESS
            } else {
                nextCtx = nextCtx.withRejectedFinish(verdict.feedback)
                    .withFailure()
                if (nextCtx.rejectedFinishCount >= MAX_REJECTED_FINISHES) {
                    terminal = StepResult.TerminalState.FAIL
                }
            }
        } else if (!verification.isExpected) {
            nextCtx = nextCtx.withFailure()
            val legacy = reflectionAgent.reflect(observed, screenAfter, action, verifiedSuccess = false)
            reflection = ReflectionOutcome(
                confirmed = false,
                reason = legacy.reason,
                feedback = legacy.reason,
                recoveryStrategy = legacy.remedialAction?.describeAction()
            )
            if (nextCtx.consecutiveFailures >= limits.watchdogLoopThreshold) {
                // Loop guard: hand back a remedial action rather than spinning on the same miss.
                // Repeated failure must NOT call withSuccess() to reset the counter!
            }
        } else {
            nextCtx = nextCtx.withSuccess()
        }

        return finish(
            action, proposal, nextCtx, screenAfter, resolvedNode,
            terminal, verification.reason, start, verification, reflection, liveCommand,
            dispatched = true
        )
    }

    private fun finish(
        action: AniobAction,
        proposal: Proposal,
        nextCtx: TaskContext,
        screen: AniobScreenState,
        resolvedNode: AniobNode?,
        terminal: StepResult.TerminalState,
        reason: String,
        start: Long,
        verification: DeterministicVerifier.VerificationResult?,
        reflection: ReflectionOutcome? = null,
        dispatch: DispatchCommand? = null,
        dispatched: Boolean = true
    ): StepResult {
        val verified = verification?.isExpected == true && terminal != StepResult.TerminalState.FAIL && dispatched
        val record = StepRecord(
            stepIndex = nextCtx.stepIndex,
            action = action,
            provider = proposal.providerLabel,
            dispatched = dispatched,
            verified = verified,
            failureReason = if (verified) null else reason,
            resolvedNode = resolvedNode,
            latencyMs = System.currentTimeMillis() - start,
            tokensUsed = 0
        )
        // RECORD: one call site, verified-only for learning (failures must never distill).
        recordStep(record)
        if (verified && dispatched) {
            learning.onVerifiedStep(nextCtx, action, screen)
            if (terminal == StepResult.TerminalState.SUCCESS) {
                learning.onTaskSuccess(nextCtx, nextCtx.trajectory)
            }
        }
        return StepResult(
            action = action,
            dispatch = dispatch ?: DispatchCommand.NoOp("pipeline"),
            verification = verification,
            reflection = reflection,
            nextCtx = nextCtx,
            terminalState = terminal,
            resolvedNode = resolvedNode,
            latencyMs = record.latencyMs
        )
    }

    private fun historyEntry(
        action: AniobAction,
        verification: DeterministicVerifier.VerificationResult,
        proposal: Proposal
    ): String = "${proposal.providerLabel}: ${action.describeAction()} -> " +
        if (verification.isExpected) "ok" else "FAILED(${verification.reason})"

    companion object {
        /** A Finish rejected this many times becomes a hard failure (no infinite retry, no fake SUCCESS). */
        const val MAX_REJECTED_FINISHES = 3
    }
}