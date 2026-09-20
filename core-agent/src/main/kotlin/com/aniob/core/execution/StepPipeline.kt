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
    private val settle: (AniobAction, () -> AniobScreenState) -> AniobScreenState = { _, capture -> capture() }
) {

    /** A proposed next action plus the rung that produced it. Rungs differ only here. */
    sealed interface Proposal {
        val action: AniobAction
        val providerLabel: String

        data class DirectIntent(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "DIRECT_INTENT"
        }

        data class FastPath(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "FASTPATH"
        }

        data class Skill(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "SKILL"
        }

        data class Model(override val action: AniobAction, val provider: String) : Proposal {
            override val providerLabel: String get() = provider
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

    fun executeStep(
        ctx: TaskContext,
        planningScreen: AniobScreenState,
        proposal: Proposal,
        capture: () -> AniobScreenState,
        dispatch: (AniobAction) -> DispatchOutcome,
        confirm: (String) -> Boolean = { true },
        judge: (String) -> ReflectionOutcome = { ReflectionOutcome.rejected(it, it) }
    ): StepResult {
        val start = System.currentTimeMillis()
        val action = proposal.action

        // OBSERVE: always a fresh capture. Planning may reuse a cached screen; dispatch never may.
        val observed = runCatching { capture() }.getOrDefault(planningScreen)

        // GATE: safety sees the *resolved node*, not a substring of toString() (finding #7).
        val resolvedNode = AniobActionExecutor.resolveTarget(action, observed)?.node
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
                StepResult.TerminalState.FAIL, intercept.reason, start, verification = null
            )
        }
        if (intercept.requiresConfirmation && !confirm(action.describeAction())) {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, "declined: ${action.describeAction()}")
                .withFailure()
            return finish(
                action, proposal, nextCtx, observed, resolvedNode,
                StepResult.TerminalState.FAIL, "User declined confirmation", start, verification = null
            )
        }

        // DISPATCH: grounding is redone against the live tree inside the seam; a miss is never a tap.
        val outcome = runCatching { dispatch(action) }
            .getOrElse { DispatchOutcome(dispatched = false, reason = "dispatch threw: ${it.message}") }
        val liveCommand = AniobActionExecutor.planDispatch(action, observed)

        // SETTLE: idle-gate instead of a fixed sleep (finding #6).
        val screenAfter = outcome.screenAfter ?: runCatching { settle(action, capture) }.getOrDefault(observed)

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
            // Finish is PROVISIONAL: it only becomes SUCCESS with evidence (finding: no fake SUCCESS).
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
                nextCtx = nextCtx.withSuccess()
            }
        } else {
            nextCtx = nextCtx.withSuccess()
        }

        return finish(
            action, proposal, nextCtx, screenAfter, resolvedNode,
            terminal, verification.reason, start, verification, reflection, liveCommand
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
        dispatch: DispatchCommand? = null
    ): StepResult {
        val verified = verification?.isExpected == true && terminal != StepResult.TerminalState.FAIL
        val record = StepRecord(
            stepIndex = nextCtx.stepIndex,
            action = action,
            provider = proposal.providerLabel,
            dispatched = true,
            verified = verified,
            failureReason = if (verified) null else reason,
            resolvedNode = resolvedNode,
            latencyMs = System.currentTimeMillis() - start,
            tokensUsed = 0
        )
        // RECORD: one call site, verified-only for learning (failures must never distill).
        recordStep(record)
        if (verified) {
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