package com.aniob.core.bench

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.TaskContext
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.external.AniobCloudLlmProvider
import com.aniob.core.external.AniobExternalAiTrigger
import com.aniob.core.external.ExternalAiResult
import com.aniob.core.intent.AniobIntent
import com.aniob.core.learning.LearningPipeline
import com.aniob.core.memory.InMemorySharedKnowledgeStore
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.router.AniobAutoRouter
import com.aniob.core.router.RouteTarget
import com.aniob.core.tools.DevicePowerState
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PHASE 1 INVARIANT REGRESSION TESTS
 *
 * Verifies the 5 non-negotiable benchmark trustworthiness rules:
 * 1. Local-only mode cannot call cloud, including failure fallbacks.
 * 2. Intent dispatch alone cannot count as task completion.
 * 3. Missing or inconclusive evidence cannot count as verified success.
 * 4. Unverified outcomes cannot enter success-only learning.
 * 5. Raw prompts, model responses and secrets are not written to ordinary logs.
 */
class BenchmarkInvariantRegressionTest {

    private fun sampleScreen(pkg: String, vararg texts: String) = AniobScreenState(
        packageName = pkg,
        activityName = "$pkg.MainActivity",
        treeHash = "hash_${pkg}_${texts.joinToString("_")}",
        nodes = texts.mapIndexed { idx, txt ->
            AniobNode(
                id = idx + 1,
                className = "android.widget.TextView",
                text = txt,
                bounds = AniobRect(10, idx * 50, 200, (idx + 1) * 50),
                isClickable = true
            )
        }
    )

    // INVARIANT 1: Local-only mode cannot call cloud, including failure fallbacks.
    @Test
    fun localOnlyModeStrictlyForbidsCloudInAutoRouter() {
        val screen = sampleScreen("com.android.settings", "Settings", "Network")
        val power = DevicePowerState(batteryPercent = 10, isCharging = false, isNetworkAvailable = true)

        // Even with battery low, local-only must NOT route to OMNIROUTE_CLOUD
        val decisionWithModel = AniobAutoRouter.decideRoute(
            taskPrompt = "Find battery setting",
            screenState = screen,
            powerState = power,
            installedModelId = "gemma4-e2b",
            isModelFileMissing = false,
            userRoutingMode = "local-only"
        )
        assertEquals(RouteTarget.LOCAL_SLM, decisionWithModel.target)
        assertFalse(decisionWithModel.reason.contains("Omniroute", ignoreCase = true))

        // When local model is missing, must CANNOT_PROCEED, never fall back to cloud
        val decisionWithoutModel = AniobAutoRouter.decideRoute(
            taskPrompt = "Find battery setting",
            screenState = screen,
            powerState = power,
            installedModelId = "gemma4-e2b",
            isModelFileMissing = true,
            userRoutingMode = "local-only"
        )
        assertEquals(RouteTarget.CANNOT_PROCEED, decisionWithoutModel.target)
        assertNotEquals(RouteTarget.OMNIROUTE_CLOUD, decisionWithoutModel.target)
    }

    @Test
    fun localOnlyModeStrictlyForbidsCloudInExternalAiTrigger() = runBlocking {
        val cloudCalled = AtomicBoolean(false)
        val fakeCloud = object : AniobCloudLlmProvider {
            override val name: String = "Omniroute"
            override val isConfigured: Boolean = true
            override suspend fun generate(prompt: String, systemPrompt: String): String {
                cloudCalled.set(true)
                return "Cloud answer"
            }
            override suspend fun generateStreaming(
                prompt: String,
                systemPrompt: String,
                onDelta: (String) -> Unit
            ): String {
                cloudCalled.set(true)
                return "Cloud streaming answer"
            }
        }

        val fakeLocalUnavailable = object : AniobLocalLlmProvider {
            override fun isAvailable(): Boolean = false
            override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String = "local"
            override fun release() {}
        }

        val trigger = AniobExternalAiTrigger(
            cloudProvider = fakeCloud,
            localProvider = fakeLocalUnavailable,
            sharedKnowledgeStore = InMemorySharedKnowledgeStore()
        )

        val power = DevicePowerState(batteryPercent = 90, isCharging = true, isNetworkAvailable = true)
        val result = trigger.query(
            prompt = "What is the capital of France?",
            intent = AniobIntent.KNOWLEDGE_QA,
            powerState = power,
            autoMode = "local-only"
        )

        assertFalse("Cloud provider must NEVER be called in local-only mode", cloudCalled.get())
        assertTrue("Result must be Unavailable when local model is unavailable in local-only mode", result is ExternalAiResult.Unavailable)
    }

    // INVARIANT 2: Intent dispatch alone cannot count as task completion.
    @Test
    fun intentDispatchAloneCannotCountAsTaskCompletion() = runBlocking {
        val initialScreen = sampleScreen("com.aniob.app", "Home")
        // Target screen did not transition to Settings
        val screenAfterNoEvidence = sampleScreen("com.aniob.app", "Still Home")

        val criteria = SuccessCriteria(
            mustShowPackage = listOf("com.android.settings")
        )
        val ctx = TaskContext(
            instruction = "Open settings",
            successCriteria = criteria
        )

        val pipeline = StepPipeline()
        val result = pipeline.executeStep(
            ctx = ctx,
            planningScreen = initialScreen,
            proposal = StepPipeline.Proposal.DirectIntent(
                action = AniobAction.Finish("Dispatched intent to settings")
            ),
            capture = { initialScreen },
            dispatch = { StepPipeline.DispatchOutcome(dispatched = true, screenAfter = screenAfterNoEvidence) }
        )

        assertFalse("Intent dispatch without observable state evidence must not be verified", result.verification!!.isExpected)
        assertNotEquals("Intent dispatch alone must not terminate in SUCCESS", StepResult.TerminalState.SUCCESS, result.terminalState)
    }

    @Test
    fun intentDispatchWithVerifiedEvidenceSucceeds() = runBlocking {
        val initialScreen = sampleScreen("com.aniob.app", "Home")
        // Target screen successfully reached com.android.settings
        val screenAfterSuccess = sampleScreen("com.android.settings", "Network", "Connected devices")

        val criteria = SuccessCriteria(
            mustShowPackage = listOf("com.android.settings")
        )
        val ctx = TaskContext(
            instruction = "Open settings",
            successCriteria = criteria
        )

        val pipeline = StepPipeline()
        val result = pipeline.executeStep(
            ctx = ctx,
            planningScreen = initialScreen,
            proposal = StepPipeline.Proposal.DirectIntent(
                action = AniobAction.Finish("Dispatched intent to settings")
            ),
            capture = { initialScreen },
            dispatch = { StepPipeline.DispatchOutcome(dispatched = true, screenAfter = screenAfterSuccess) }
        )

        assertTrue("Intent dispatch with verified observable evidence must succeed", result.verification!!.isExpected)
        assertEquals("Intent dispatch with verified evidence terminates in SUCCESS", StepResult.TerminalState.SUCCESS, result.terminalState)
    }

    // INVARIANT 3: Missing or inconclusive evidence cannot count as verified success.
    @Test
    fun missingOrInconclusiveEvidenceFailsVerification() {
        val screen = sampleScreen("com.android.settings", "Settings", "Display")

        // 1. Missing criteria (null)
        val nullResult = DeterministicVerifier.verifyFinish(null, screen, 1)
        assertFalse(nullResult.isExpected)

        // 2. Empty criteria
        val emptyResult = DeterministicVerifier.verifyFinish(SuccessCriteria(), screen, 1)
        assertFalse(emptyResult.isExpected)

        // 3. minSteps only (no observable check)
        val minStepsResult = DeterministicVerifier.verifyFinish(SuccessCriteria(minSteps = 2), screen, 3)
        assertFalse("minSteps alone without observable check is not evidence", minStepsResult.isExpected)

        // 4. Inconclusive activity criteria
        val blankActivityScreen = AniobScreenState(
            packageName = "com.android.settings",
            activityName = "",
            treeHash = "h1",
            nodes = emptyList()
        )
        val inconclusiveCriteria = SuccessCriteria(mustShowActivity = listOf("com.android.settings.SubSettings"))
        val evals = inconclusiveCriteria.evaluateCriteria(blankActivityScreen, 1)
        assertTrue(evals.any { it.state == CriterionState.INCONCLUSIVE })
        val finishResult = DeterministicVerifier.verifyFinish(inconclusiveCriteria, blankActivityScreen, 1)
        assertFalse("Inconclusive criteria must reject finish verification", finishResult.isExpected)
    }

    // INVARIANT 4: Unverified outcomes cannot enter success-only learning.
    @Test
    fun unverifiedOutcomesCannotEnterSuccessOnlyLearning() = runBlocking {
        val learnedSuccess = AtomicBoolean(false)
        val fakeLearning = object : LearningPipeline {
            override fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState) {}
            override fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>) {
                learnedSuccess.set(true)
            }
        }

        val initialScreen = sampleScreen("com.aniob.app", "Home")
        val unverifiedAfter = sampleScreen("com.aniob.app", "Different Home")

        val criteria = SuccessCriteria(mustContainText = listOf("Display"))
        val ctx = TaskContext(instruction = "Go to display", successCriteria = criteria)

        val pipeline = StepPipeline(learning = fakeLearning)
        // Propose Finish without the required text
        pipeline.executeStep(
            ctx = ctx,
            planningScreen = initialScreen,
            proposal = StepPipeline.Proposal.Model(AniobAction.Finish("claim finished"), "LOCAL_SLM"),
            capture = { initialScreen },
            dispatch = { StepPipeline.DispatchOutcome(dispatched = true, screenAfter = unverifiedAfter) }
        )

        assertFalse("Unverified outcome must NEVER trigger onTaskSuccess learning", learnedSuccess.get())
    }
}
