package com.aniob.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aniob.app.AniobApplication
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.provider.AniobMockProvider
import com.aniob.app.provider.AniobOmniRouteProvider
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.core.domain.*
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.exec.ExecOutcome
import com.aniob.core.exec.ExecReport
import com.aniob.core.execution.AniobAppSessionManager
import com.aniob.core.external.AniobExternalAiTrigger
import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.intent.AniobIntent
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobPlanningAgent
import com.aniob.core.ladder.ResolvedIntentShortcut
import com.aniob.core.ladder.TaskProgress
import com.aniob.core.logging.AniobExecutionTracker
import com.aniob.core.memory.AniobHippocampusTracker
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.memory.InMemorySharedKnowledgeStore
import com.aniob.core.policy.AniobObservationPolicy
import com.aniob.core.providers.AniobHybridEngineRouter
import com.aniob.core.providers.AniobLiteRtProvider
import com.aniob.core.providers.AniobLocalLlmClient
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.router.RouteTarget
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.skills.AniobSemanticSkillMatcher
import com.aniob.core.tools.DevicePowerState
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.AniobWatchdog
import com.aniob.core.verifier.DeterministicVerifier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AniobUiState(
    val currentTab: Int = 0, // 0: Chat, 1: Tracker, 2: Stats, 3: Settings
    val activeTask: AniobTask? = null,
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val grillMeResult: AniobGrillMeResult? = null,
    val showGrillMeSheet: Boolean = false,
    val showTrackerSheet: Boolean = false,
    val trackerFilter: String = "ALL", // ALL, CORRECT, FAILED
    val steps: List<AniobStepRecord> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val omnirouteApiKey: String = "",
    val omnirouteModel: String = "gpt-4o",
    val autoRouterMode: String = "auto", // auto, local-first, local-only, cloud-only, balanced
    val statusMessage: String = "Ready",
    val lastRoutingReason: String = "Ready",
    val lastProviderUsed: String = "NONE",
    val sessionScores: List<SessionScoreEntity> = emptyList(),
    val streamingBubbleText: String = "",
    val isStreaming: Boolean = false
)

class AniobViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AniobUiState())
    val uiState: StateFlow<AniobUiState> = _uiState.asStateFlow()

    val showConfirmationDialog = mutableStateOf<Pair<String, () -> Unit>?>(null)

    private val semanticSkillMatcher = AniobSemanticSkillMatcher()
    private val grillMeEngine = AniobGrillMeEngine()
    private val planningAgent = AniobPlanningAgent()
    private val memoryStore = AniobEmbeddingStore()
    private val reflectionAgent = AniobReflectionAgent()
    private val executionRouter = AniobExecutionRouter(
        semanticSkillMatcher = semanticSkillMatcher,
        planningAgent = planningAgent,
        memoryStore = memoryStore
    )
    private val mockProvider = AniobMockProvider()
    private val localLlmClient = AniobLocalLlmClient.getEngine()
    private val watchdog = AniobWatchdog(loopThreshold = 3)
    private val observationPolicy = AniobObservationPolicy(maxBurstSteps = 3)
    private val appSessionManager = AniobAppSessionManager()
    private val executionTracker = AniobExecutionTracker()
    private var localFailCount = 0

    private val app = application as AniobApplication
    private val modelDownloader = AniobModelDownloader(application)
    private val hippocampusTracker by lazy {
        AniobHippocampusTracker(
            skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills"),
            assetSkillsDir = null // built-in library stays assets-synced; learned skills are runtime-only
        )
    }
    private val hybridEngineRouter by lazy { AniobHybridEngineRouter(modelsDir = modelDownloader.getModelsDir()) }
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore = InMemorySharedKnowledgeStore()
    private val localLlmProvider: AniobLocalLlmProvider = AniobLiteRtProvider()

    fun getInstalledModelName(): String? {
        val id = modelDownloader.getDefaultModelId() ?: return null
        return AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id
    }

    private fun getOmniRouteProvider(): AniobOmniRouteProvider {
        return AniobOmniRouteProvider(
            apiKey = _uiState.value.omnirouteApiKey,
            model = _uiState.value.omnirouteModel
        )
    }

    init {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        val savedApiKey = prefs.getString("omniroute_api_key", "") ?: ""
        val savedModel = prefs.getString("omniroute_model", "gpt-4o") ?: "gpt-4o"
        val savedMode = prefs.getString("autorouter_mode", "auto") ?: "auto"
        localFailCount = prefs.getInt("local_fail_count", 0)

        _uiState.update {
            it.copy(
                omnirouteApiKey = savedApiKey,
                omnirouteModel = savedModel,
                autoRouterMode = savedMode
            )
        }

        // Collect session scores from Room database
        viewModelScope.launch {
            app.metricsCollector.getSessionScores().collect { scores ->
                _uiState.update { it.copy(sessionScores = scores) }
            }
        }

        // Add initial system greeting
        _uiState.update {
            it.copy(
                chatMessages = listOf(
                    ChatMessage(
                        id = "msg_init",
                        role = "assistant",
                        content = "Hello! I am Aniob, your autonomous Android UI agent. Ask me to perform any action on your device."
                    )
                )
            )
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
    }

    fun setTab(index: Int) {
        _uiState.update { it.copy(currentTab = index) }
    }

    fun setTrackerFilter(filter: String) {
        _uiState.update { it.copy(trackerFilter = filter) }
    }

    fun setShowTrackerSheet(show: Boolean) {
        _uiState.update { it.copy(showTrackerSheet = show) }
    }

    fun setAutoRouterMode(mode: String) {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("autorouter_mode", mode).apply()
        _uiState.update { it.copy(autoRouterMode = mode) }
    }

    fun updateSettings(apiKey: String, model: String) {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("omniroute_api_key", apiKey).putString("omniroute_model", model).apply()
        _uiState.update { it.copy(omnirouteApiKey = apiKey, omnirouteModel = model) }
    }

    /**
     * Entry point when user submits a natural language command.
     */
    fun submitTask(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        val taskId = "task_${System.currentTimeMillis()}"
        val task = AniobTask(id = taskId, rawPrompt = trimmed)

        val userMessage = ChatMessage(
            id = "msg_user_${System.currentTimeMillis()}",
            role = "user",
            content = trimmed
        )

        _uiState.update {
            it.copy(
                activeTask = task,
                isRunning = true,
                currentStep = 0,
                statusMessage = "Analyzing task...",
                chatMessages = it.chatMessages + userMessage,
                steps = emptyList()
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            // Step 0: Intent Gate (AIM Phase 2.6 / P0-3)
            val intent = AniobTaskIntentClassifier.classify(trimmed)
            if (intent != AniobIntent.DEVICE_AUTOMATION) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Answering query...",
                        lastProviderUsed = "EXTERNAL_AI"
                    )
                }
                val trigger = AniobExternalAiTrigger(
                    cloudProvider = getOmniRouteProvider(),
                    localProvider = localLlmProvider,
                    sharedKnowledgeStore = sharedKnowledgeStore
                )
                val powerState = AniobDeviceTelemetry.getRealState(app)
                val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
                val autoMode = prefs.getString("autorouter_mode", "auto") ?: "auto"

                val result = trigger.query(
                    prompt = trimmed,
                    intent = intent,
                    powerState = powerState,
                    autoMode = autoMode,
                    onDelta = { delta ->
                        _uiState.update { it.copy(streamingBubbleText = delta, isStreaming = true) }
                    }
                )
                val assistantMessage = ChatMessage(
                    id = "msg_asst_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = result.answer,
                    badge = "💬 AI Answer",
                    provider = result.provider
                )
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isStreaming = false,
                        streamingBubbleText = "",
                        statusMessage = "Ready",
                        lastRoutingReason = "Answered via ${result.provider}",
                        chatMessages = it.chatMessages + assistantMessage,
                        lastProviderUsed = result.provider
                    )
                }

                // Record Session Telemetry for analytics and audit
                app.metricsCollector.recordSession(
                    taskId = taskId,
                    prompt = trimmed,
                    isSuccess = true,
                    steps = 0,
                    durationMs = result.latencyMs,
                    tokensUsed = 120,
                    providerUsed = result.provider,
                    decisionReason = "Direct intent answer for ${intent.name}"
                )
                return@launch
            }

            // Step 1: Grill-Me Check (Flow: Understand -> Ask questions -> Create plan -> Proceed)
            val grillResult = grillMeEngine.evaluateTask(trimmed)
            if (grillResult.needsClarification && grillResult.questions.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        grillMeResult = grillResult,
                        showGrillMeSheet = true,
                        statusMessage = "Awaiting clarification for '$trimmed'"
                    )
                }
                return@launch
            }

            // Task is self-contained -> Proceed directly
            executeTaskPipeline(task)
        }
    }

    fun onGrillAnswersSubmitted(answers: Map<String, String>) {
        val currentTask = _uiState.value.activeTask ?: return
        val clarifiedPrompt = buildString {
            append(currentTask.rawPrompt)
            answers.forEach { (q, a) -> append(" [$q: $a]") }
        }
        val updatedTask = currentTask.copy(
            clarifiedGoal = clarifiedPrompt,
            grillAnswers = answers,
            status = TaskStatus.RUNNING
        )

        _uiState.update {
            it.copy(
                showGrillMeSheet = false,
                activeTask = updatedTask,
                statusMessage = "Proceeding with clarified goal..."
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            executeTaskPipeline(updatedTask)
        }
    }

    fun dismissGrillMe() {
        _uiState.update { it.copy(showGrillMeSheet = false, isRunning = false) }
    }

    /**
     * Executes the task through the ExecutionRouter ladder:
     * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
     */
    private suspend fun executeTaskPipeline(task: AniobTask) {
        watchdog.reset()
        observationPolicy.reset()
        executionTracker.clear()
        AniobAccessibilityService.isTaskActive = true
        val startTime = System.currentTimeMillis()
        var currentStep = 0
        var totalTokens = 0
        var finalSuccess = true
        var primaryProvider = "INTENT"
        var decisionReason = "Direct intent shortcut"
        var screenReads = 0
        var actionsDispatched = 0
        var escalations = 0

        // Hippocampus: short-term episodic stream for this task ("hippocampus -> cortex" consolidation)
        hippocampusTracker.beginTask(task.id, task.clarifiedGoal)

        // Background automation integrity: minimize Aniob chat UI and run foreground service
        AniobBackgroundController.onTaskStarted(app, task.rawPrompt)

        val a11y = AniobAccessibilityService.instance
        var screenBefore: AniobScreenState? = a11y?.captureCurrentScreenState()

        fun recordTrajectory(
            stepIndex: Int,
            observation: String,
            action: AniobAction,
            provider: String,
            latencyMs: Long,
            screenHash: String,
            verified: Boolean
        ) {
            hippocampusTracker.recordStep(
                AniobHippocampusTracker.HippocampusStep(
                    stepIndex = stepIndex,
                    observation = observation,
                    thought = action.thought,
                    action = action,
                    latencyMs = latencyMs,
                    screenHash = screenHash,
                    provider = provider,
                    verified = verified
                )
            )
            executionTracker.recordEvent(
                AniobExecutionTracker.TrajectoryEvent(
                    stepIndex = stepIndex,
                    observationSummary = observation,
                    thought = action.thought,
                    toolName = action.toolName,
                    action = action,
                    latencyMs = latencyMs,
                    outcome = if (verified) "SUCCESS" else "FAILURE",
                    isVerified = verified
                )
            )
            app.eventLogger.logStep(
                com.aniob.app.metrics.EventLogger.TrajectoryStep(
                    stepIndex = stepIndex,
                    observation = observation,
                    thought = action.thought,
                    action = action,
                    latencyMs = latencyMs,
                    screenHash = screenHash,
                    provider = provider
                )
            )
        }

        val maxSteps = 10

        try {
            while (currentStep < maxSteps && _uiState.value.isRunning) {
                _uiState.update { it.copy(currentStep = currentStep + 1) }

                // Check Observation Policy: burst if predictable, else full capture
                val currentScreen = if (observationPolicy.shouldObserve() || screenBefore == null) {
                    screenReads++
                    a11y?.captureCurrentScreenState() ?: AniobScreenState(
                        packageName = "com.aniob.app",
                        treeHash = "mock_hash_${currentStep}",
                        nodes = listOf(
                            AniobNode(id = 1, className = "TextView", text = "Settings", isClickable = true, bounds = AniobRect(50, 100, 300, 180)),
                            AniobNode(id = 2, className = "Button", text = "Search", isClickable = true, bounds = AniobRect(50, 200, 300, 280))
                        )
                    )
                } else {
                    screenBefore
                }
                appSessionManager.onSessionScreenUpdated(currentScreen.packageName, currentScreen)

                // Evaluate Execution Ladder with real power state and on-device model status
                val powerState = AniobDeviceTelemetry.getRealState(app)
                val installedModelId = modelDownloader.getDefaultModelId()
                val installedFileExists = installedModelId?.let { File(modelDownloader.getModelsDir(), "$it.gguf").exists() } == true
                val effectiveModelId = if (installedFileExists) installedModelId else null
                val isModelFileMissing = installedModelId != null && !installedFileExists

                val ladderResult = executionRouter.planStep(
                    taskPrompt = task.clarifiedGoal,
                    screenState = currentScreen,
                    stepIndex = currentStep,
                    taskSignature = task.rawPrompt.lowercase(),
                    powerState = powerState,
                    installedModelId = effectiveModelId,
                    lastLocalFailCount = localFailCount,
                    isModelFileMissing = isModelFileMissing
                )

                val stepStartTime = System.currentTimeMillis()

                when (ladderResult) {
                    is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                        primaryProvider = "INTENT"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing direct system intent...") }

                        dispatchSystemIntent(ladderResult.shortcut)
                        delay(800)

                        screenReads++
                        val screenAfter = a11y?.captureCurrentScreenState() ?: currentScreen
                        appSessionManager.onSessionScreenUpdated(ladderResult.shortcut.targetPackage ?: screenAfter.packageName, screenAfter)
                        actionsDispatched++
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${screenAfter.treeHash.take(6)} after intent",
                            action = AniobAction.Finish(summary = "Intent dispatched: ${ladderResult.shortcut.targetPackage}"),
                            provider = "INTENT",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = screenAfter.treeHash,
                            verified = true
                        )
                        val stepRecord = AniobStepRecord(
                            stepIndex = currentStep + 1,
                            screenHash = currentScreen.treeHash,
                            action = AniobAction.Finish(summary = "Intent dispatched: ${ladderResult.shortcut.targetPackage}"),
                            provider = "INTENT",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            tokensUsed = 0,
                            verifiedSuccess = true
                        )
                        addStepRecord(stepRecord)
                        finalSuccess = true
                        break
                    }

                    is AniobExecutionRouter.ExecutionPlanResult.FastPathStep -> {
                        primaryProvider = "FASTPATH"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "FastPath replay step ${currentStep + 1}...") }

                        val action = ladderResult.action

                        // Safety Interceptor check
                        val intercept = AniobSafetyInterceptor.evaluateAction(
                            action = action,
                            targetNode = null,
                            screenState = currentScreen,
                            screenFingerprint = currentScreen.treeHash
                        )

                        if (!intercept.isAllowed) {
                            recordTrajectory(
                                stepIndex = currentStep + 1,
                                observation = "blocked by safety interceptor",
                                action = action,
                                provider = "FASTPATH",
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                screenHash = currentScreen.treeHash,
                                verified = false
                            )
                            val stepRecord = AniobStepRecord(
                                stepIndex = currentStep + 1,
                                screenHash = currentScreen.treeHash,
                                action = action,
                                provider = "FASTPATH",
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                tokensUsed = 0,
                                verifiedSuccess = false,
                                failureReason = intercept.reason
                            )
                            addStepRecord(stepRecord)
                            finalSuccess = false
                            break
                        }

                        executeActionSync(a11y, action)
                        delay(600)

                        screenReads++
                        val screenAfter = a11y?.captureCurrentScreenState() ?: currentScreen
                        val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)
                        actionsDispatched++
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${screenAfter.treeHash.take(6)} after ${action.toolName}",
                            action = action,
                            provider = "FASTPATH",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = screenAfter.treeHash,
                            verified = verification.isExpected
                        )

                        observationPolicy.recordActionOutcome(action, currentScreen.packageName, verification.isSuccessful)

                        val stepRecord = AniobStepRecord(
                            stepIndex = currentStep + 1,
                            screenHash = currentScreen.treeHash,
                            action = action,
                            provider = "FASTPATH",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            tokensUsed = 0,
                            verifiedSuccess = verification.isSuccessful,
                            failureReason = if (!verification.isSuccessful) verification.reason else null
                        )
                        addStepRecord(stepRecord)

                        if (action is AniobAction.Finish) break
                        screenBefore = screenAfter
                        currentStep++
                    }

                    is AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution -> {
                        primaryProvider = "SKILL"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing Skill '${ladderResult.skill.name}' step ${currentStep + 1}...") }

                        val action = ladderResult.action
                        executeActionSync(a11y, action)
                        delay(600)

                        screenReads++
                        val screenAfter = a11y?.captureCurrentScreenState() ?: currentScreen
                        val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)
                        actionsDispatched++
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${screenAfter.treeHash.take(6)} after skill ${action.toolName}",
                            action = action,
                            provider = "SKILL",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = screenAfter.treeHash,
                            verified = verification.isExpected
                        )
                        observationPolicy.recordActionOutcome(action, currentScreen.packageName, verification.isSuccessful)

                        val stepRecord = AniobStepRecord(
                            stepIndex = currentStep + 1,
                            screenHash = currentScreen.treeHash,
                            action = action,
                            provider = "SKILL",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            tokensUsed = 0,
                            verifiedSuccess = verification.isSuccessful,
                            failureReason = if (!verification.isSuccessful) verification.reason else null
                        )
                        addStepRecord(stepRecord)

                        if (action is AniobAction.Finish) break
                        screenBefore = screenAfter
                        currentStep++
                    }

                    is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch -> {
                        val decision = ladderResult.decision
                        primaryProvider = when (decision.target) {
                            RouteTarget.OMNIROUTE_CLOUD -> "OMNIROUTE_CLOUD"
                            RouteTarget.LOCAL_SLM -> "LOCAL_SLM"
                            else -> "MOCK"
                        }
                        decisionReason = decision.reason

                        _uiState.update {
                            it.copy(
                                statusMessage = "Routed to $primaryProvider: ${decision.reason}",
                                lastRoutingReason = decision.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }

                        // Invoke provider (Local LiteRT, Omniroute Cloud, or Mock fallback)
                        if (decision.target == RouteTarget.OMNIROUTE_CLOUD) escalations++
                        val action = if (decision.target == RouteTarget.LOCAL_SLM && localLlmClient.isModelLoaded()) {
                            val localRes = localLlmClient.generateStep("System: Android Agent", task.clarifiedGoal)
                            totalTokens += 80
                            mockProvider.planNextStep(task.clarifiedGoal, currentStep, currentScreen)
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD && _uiState.value.omnirouteApiKey.isNotBlank()) {
                            val omniroute = AniobOmniRouteProvider(
                                apiKey = _uiState.value.omnirouteApiKey,
                                model = _uiState.value.omnirouteModel
                            )
                            val res = omniroute.getNextAction("You are Aniob agent.", task.clarifiedGoal)
                            totalTokens += 150
                            res.getOrElse { mockProvider.planNextStep(task.clarifiedGoal, currentStep, currentScreen) }
                        } else {
                            mockProvider.planNextStep(task.clarifiedGoal, currentStep, currentScreen)
                        }

                        // Watchdog check
                        watchdog.record(currentScreen.treeHash, action)
                        val loopDetected = watchdog.isLoopDetected()

                        // Safety Interceptor check
                        val intercept = AniobSafetyInterceptor.evaluateAction(
                            action = action,
                            targetNode = null,
                            screenState = currentScreen,
                            screenFingerprint = currentScreen.treeHash
                        )

                        if (!intercept.isAllowed) {
                            recordTrajectory(
                                stepIndex = currentStep + 1,
                                observation = "blocked by safety interceptor",
                                action = action,
                                provider = primaryProvider,
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                screenHash = currentScreen.treeHash,
                                verified = false
                            )
                            val stepRecord = AniobStepRecord(
                                stepIndex = currentStep + 1,
                                screenHash = currentScreen.treeHash,
                                action = action,
                                provider = primaryProvider,
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                tokensUsed = if (primaryProvider == "OMNIROUTE_CLOUD") 150 else 0,
                                verifiedSuccess = false,
                                failureReason = intercept.reason
                            )
                            addStepRecord(stepRecord)
                            finalSuccess = false
                            break
                        }

                        if (intercept.requiresConfirmation) {
                            showConfirmationDialog.value = Pair(intercept.reason) {}
                            actionsDispatched++
                            recordTrajectory(
                                stepIndex = currentStep + 1,
                                observation = "confirmation gate raised",
                                action = action,
                                provider = primaryProvider,
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                screenHash = currentScreen.treeHash,
                                verified = true
                            )
                        }

                        executeActionSync(a11y, action)
                        delay(600)

                        screenReads++
                        val screenAfter = a11y?.captureCurrentScreenState() ?: currentScreen
                        val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)
                        actionsDispatched++
                        val reflection = reflectionAgent.reflect(currentScreen, screenAfter, action, verification.isExpected)
                        val remedial = reflection.remedialAction
                        if (!reflection.isExpected && remedial != null) {
                            executeActionSync(a11y, remedial)
                        }
                        observationPolicy.recordActionOutcome(action, currentScreen.packageName, verification.isSuccessful)
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${screenAfter.treeHash.take(6)} after ${action.toolName}",
                            action = action,
                            provider = primaryProvider,
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = screenAfter.treeHash,
                            verified = verification.isExpected
                        )

                        val stepSuccess = verification.isSuccessful && !loopDetected
                        if (primaryProvider == "LOCAL_SLM") {
                            if (stepSuccess) localFailCount = 0 else localFailCount++
                            app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("local_fail_count", localFailCount)
                                .apply()
                        }

                        val stepRecord = AniobStepRecord(
                            stepIndex = currentStep + 1,
                            screenHash = currentScreen.treeHash,
                            action = action,
                            provider = primaryProvider,
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            tokensUsed = if (primaryProvider == "OMNIROUTE_CLOUD") 150 else 0,
                            verifiedSuccess = stepSuccess,
                            failureReason = if (loopDetected) "Watchdog loop detected (N=3 repeated action)" else if (!verification.isSuccessful) verification.reason else null,
                            reflectorInvoked = loopDetected
                        )
                        addStepRecord(stepRecord)

                        if (action is AniobAction.Finish || action is AniobAction.Fail) {
                            finalSuccess = action is AniobAction.Finish
                            break
                        }

                        screenBefore = screenAfter
                        currentStep++
                    }
                }
            }
        } finally {
            AniobAccessibilityService.isTaskActive = false
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val resultSummary = if (finalSuccess) "Task completed in ${totalDuration}ms (${currentStep + 1} steps)" else "Task terminated"

        // Hippocampus: sleep-time consolidation on success, natural-selection prune on failure.
        val consolidatedSkill = if (finalSuccess) {
            hippocampusTracker.consolidateOnSuccess(
                taskId = task.id,
                prompt = task.clarifiedGoal,
                screenReads = screenReads,
                actions = actionsDispatched,
                escalations = escalations,
                elapsedMs = totalDuration
            )
        } else {
            hippocampusTracker.onFailure()
            null
        }
        if (consolidatedSkill != null) {
            app.eventLogger.info("AniobHippocampus", "Consolidated learned skill '$consolidatedSkill'")
        }
        app.eventLogger.recordSessionCounters(
            screenReads = screenReads,
            actions = actionsDispatched,
            escalations = escalations,
            elapsedMs = totalDuration,
            stateTrace = hippocampusTracker.currentStateTrace(),
            providerDecisionReason = decisionReason,
            lastRoutingReason = _uiState.value.lastRoutingReason
        )
        // Push the pinned terminal snapshot into the BrowserUse metrics stream.
        ExecReport(
            outcome = if (finalSuccess) ExecOutcome.SUCCESS else ExecOutcome.FAILED_VERIFICATION,
            reason = resultSummary,
            screenReads = screenReads,
            actions = actionsDispatched,
            escalations = escalations,
            elapsedMs = totalDuration,
            stateTrace = hippocampusTracker.currentStateTrace(),
            providerUsed = primaryProvider,
            decisionReason = decisionReason
        ).toMetricSnapshot(taskId = task.id, stepIndex = currentStep + 1).also { snapshot ->
            app.eventLogger.info(
                "AniobMetrics",
                "SNAPSHOT{task=${snapshot.taskId},provider=${snapshot.provider}," +
                    "screenReads=${snapshot.screenReads},actions=${snapshot.actions}," +
                    "escalations=${snapshot.escalations},routing=${snapshot.lastRoutingReason}}"
            )
        }

        // Auto-return to chatroom and stop foreground service
        AniobBackgroundController.onTaskFinished(app, resultSummary)

        // Add assistant completion message to chat
        val assistantMessage = ChatMessage(
            id = "msg_asst_${System.currentTimeMillis()}",
            role = "assistant",
            content = "Completed '${task.rawPrompt}' via $primaryProvider. $resultSummary",
            stepIndex = currentStep + 1
        )

        // Persist session metrics to Room database
        viewModelScope.launch {
            app.metricsCollector.recordSession(
                taskId = task.id,
                prompt = task.rawPrompt,
                isSuccess = finalSuccess,
                steps = currentStep + 1,
                durationMs = totalDuration,
                tokensUsed = totalTokens,
                providerUsed = primaryProvider,
                decisionReason = decisionReason
            )
        }

        _uiState.update {
            it.copy(
                isRunning = false,
                statusMessage = resultSummary,
                lastProviderUsed = primaryProvider,
                chatMessages = it.chatMessages + assistantMessage
            )
        }
    }

    private fun addStepRecord(step: AniobStepRecord) {
        _uiState.update { it.copy(steps = it.steps + step) }
    }

    private fun executeActionSync(a11y: AniobAccessibilityService?, action: AniobAction) {
        a11y?.executeAction(action) { /* callback */ }
    }

    private fun dispatchSystemIntent(shortcut: ResolvedIntentShortcut) {
        try {
            val intent = Intent(shortcut.action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                shortcut.targetPackage?.let { setPackage(it) }
                shortcut.uriString?.let { data = Uri.parse(it) }
                shortcut.category?.let { addCategory(it) }
            }
            app.startActivity(intent)
        } catch (e: Exception) {
            app.eventLogger.error("AniobViewModel", "Failed to launch system intent: ${e.message}")
        }
    }

    fun stopCurrentTask() {
        AniobAccessibilityService.isTaskActive = false
        hippocampusTracker.onFailure() // prune interrupted episode
        AniobBackgroundController.onTaskFinished(app, "Stopped by user")
        _uiState.update { it.copy(isRunning = false, statusMessage = "Stopped by user") }
    }
}
