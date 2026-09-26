package com.aniob.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.aniob.app.AniobApplication
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.provider.AniobOmniRouteProvider
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.AniobExecutionEvents
import com.aniob.app.ui.model.BlockedRecord
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.MomentChip
import com.aniob.app.ui.model.NextAction
import com.aniob.app.ui.model.Outcome
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.app.ui.model.StepEvent
import com.aniob.app.ui.model.TaskSummary
import com.aniob.core.narration.StepNarration
import com.aniob.core.domain.*
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.exec.ExecOutcome
import com.aniob.core.exec.ExecReport
import com.aniob.core.execution.AniobAppSessionManager
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import com.aniob.core.policy.AniobWaitForIdle
import com.aniob.core.providers.AniobGenerationResult
import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.providers.AniobStructuredOutput
import com.aniob.core.providers.AniobLocalActionResolver
import com.aniob.core.providers.AniobHybridEngineRouter
import com.aniob.core.providers.AniobLiteRtProvider
import com.aniob.core.providers.AniobLocalLlmClient
import com.aniob.core.providers.AniobLocalLlmEngine
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.router.RouteTarget
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.skills.AniobSemanticSkillMatcher
import com.aniob.core.tools.AniobScrollHelper
import com.aniob.core.tools.DevicePowerState
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.AniobWatchdog
import com.aniob.core.verifier.DeterministicVerifier
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AniobUiState(
    val activeTask: AniobTask? = null,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentStep: Int = 0,
    val grillMeResult: AniobGrillMeResult? = null,
    val showGrillMeSheet: Boolean = false,
    val showTrackerSheet: Boolean = false,
    val trackerFilter: String = "ALL", // ALL, CORRECT, FAILED
    val steps: List<AniobStepRecord> = emptyList(),
    val stepEvents: List<StepEvent> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val omnirouteApiKey: String = "",
    val omnirouteModel: String = "gpt-4o",
    val autoRouterMode: String = "auto", // auto, local-first, local-only, cloud-only, balanced
    val statusMessage: String = "Ready",
    val lastRoutingReason: String = "Ready",
    val lastProviderUsed: String = "NONE",
    val sessionScores: List<SessionScoreEntity> = emptyList(),
    val streamingBubbleText: String = "",
    val isStreaming: Boolean = false,
    /** UX-6: honest recovery state after a process death mid-task. */
    val interruptedSummary: TaskSummary? = null,
    /** UX-6: last completed task, rendered as a card on next open (Quiet Return). */
    val lastSummary: TaskSummary? = null,
    /** UX-6: a11y was revoked mid-run; recovery banner until the user re-enables. */
    val accessibilityRecoveryNeeded: Boolean = false,
    /** UX-4: pending approval the user must actually see. */
    val confirmRequest: ConfirmRequest? = null,
    /** UX-3: the last narration line, for the ticker and lock-adjacent surfaces. */
    val lastNarration: String = "",
    /** UX-2/UX-5: Remembered Moments chips are ephemeral and shown above the input. */
    val momentChips: List<MomentChip> = emptyList(),
    /** UX-2: grill questions already answered from the vault, rendered with a "saved ✓" badge. */
    val grillRememberedKeys: Set<String> = emptySet(),
    /** UX-4: Trust Center controls — real state, never placebo. */
    val safetyLevel: SafetyLevel = SafetyLevel.STANDARD,
    val fastPathEnabled: Boolean = true,
    val safetyGateEnabled: Boolean = true,
    /** UX-4: last 20 approval decisions with context. */
    val confirmationHistory: List<ConfirmationRecord> = emptyList(),
    /** UX-4: fingerprints the interceptor has hard-blocked, with rule + time. */
    val blockedActions: List<BlockedRecord> = emptyList(),
    /** UX-2: follow-up queued while a task runs (depth 1, honest label). */
    val queuedPrompt: String? = null,
    /** UX-5: names of skills the user has disabled; matcher skips these. */
    val disabledSkills: Set<String> = emptySet(),
    /** Staged draft from share or incoming intent that requires user confirmation/submission */
    val pendingInputPrefill: String? = null
)

class AniobViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AniobUiState())

    /** Back-stack owned by the ViewModel (UX-0 §2). Survives rotation; root CHAT is re-derived. */
    private val _navBackStack = MutableStateFlow(restoreNavBackStack())
    val navBackStack: StateFlow<List<AniobRoute>> = _navBackStack.asStateFlow()

    private val _isReadyToRender = MutableStateFlow(false)
    val isReadyToRender: StateFlow<Boolean> = _isReadyToRender.asStateFlow()

    val currentRoute: AniobRoute get() = _navBackStack.value.lastOrNull() ?: AniobRoute.CHAT

    private fun restoreNavBackStack(): List<AniobRoute> {
        val saved = savedStateHandle.get<String>(KEY_NAV_BACKSTACK) ?: return listOf(AniobRoute.CHAT)
        val routes = saved.split(",").mapNotNull { name ->
            runCatching { AniobRoute.valueOf(name) }.getOrNull()
        }
        return routes.ifEmpty { listOf(AniobRoute.CHAT) }
    }

    private fun persistNavBackStack(stack: List<AniobRoute>) {
        savedStateHandle[KEY_NAV_BACKSTACK] = stack.joinToString(",") { it.name }
    }

    fun navigateTo(route: AniobRoute) {
        val stack = _navBackStack.value
        if (stack.lastOrNull() == route) return
        val updated = stack + route
        _navBackStack.value = updated
        persistNavBackStack(updated)
    }

    /** Pops one screen. Returns false when at the root (caller lets the system handle Back). */
    fun navigateBack(): Boolean {
        val stack = _navBackStack.value
        if (stack.size <= 1) return false
        val updated = stack.dropLast(1)
        _navBackStack.value = updated
        persistNavBackStack(updated)
        return true
    }

    fun resetToChat() {
        _navBackStack.value = listOf(AniobRoute.CHAT)
        persistNavBackStack(listOf(AniobRoute.CHAT))
    }

    /**
     * Deep-link entry point (UX-0 §2): `aniob://chat|tracker|confirm|result|skills`.
     * Returns true when the link was recognised and handled.
     */
    fun handleDeepLink(uri: String?): Boolean {
        if (uri.isNullOrBlank() || !uri.startsWith("aniob://")) return false
        val host = uri.removePrefix("aniob://").substringBefore('/').substringBefore('?').lowercase()
        return when (host) {
            "chat" -> { resetToChat(); true }
            "tracker" -> { resetToChat(); setShowTrackerSheet(true); true }
            "result" -> { resetToChat(); true }
            "skills" -> { navigateTo(AniobRoute.SKILLS); true }
            "confirm" -> true
            else -> false
        }
    }

    val uiState: StateFlow<AniobUiState> = _uiState.asStateFlow()

    private val semanticSkillMatcher = AniobSemanticSkillMatcher()
    private val grillMeEngine = AniobGrillMeEngine()
    private val planningAgent = AniobPlanningAgent()
    private val memoryStore = AniobEmbeddingStore()
    private val reflectionAgent = AniobReflectionAgent()
    internal val executionRouter = AniobExecutionRouter(
        semanticSkillMatcher = semanticSkillMatcher,
        planningAgent = planningAgent,
        memoryStore = memoryStore
    )
    /** The single learning pipeline; shares the router's FastPath engine and Room. */
    private val learningPipeline = com.aniob.app.learning.AniobLearningPipelineImpl(
        database = (application as AniobApplication).database,
        replayEngine = executionRouter.fastPathEngine
    )
    private var localLlmClient: AniobLocalLlmEngine = AniobLocalLlmClient.getEngine()
    private val watchdog = AniobWatchdog(loopThreshold = 3)
    private val observationPolicy = AniobObservationPolicy(maxBurstSteps = 3)
    private val appSessionManager = AniobAppSessionManager()
    private val executionTracker = AniobExecutionTracker()
    private var localFailCount = 0

    // Tracker zero-capture guarantee: opening the sheet is a pure view over recorded state.
    private var trackerOpenCount = 0

    private val app = application as AniobApplication
    private val modelDownloader = AniobModelDownloader(application)
    private val configLoader = com.aniob.app.config.AniobConfigLoader(application)
    private val agentLimits: com.aniob.core.config.AgentLimits by lazy { configLoader.agentLimits() }
    private val hippocampusTracker by lazy {
        AniobHippocampusTracker(
            skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills"),
            assetSkillsDir = null // built-in library stays assets-synced; learned skills are runtime-only
        )
    }
    private val hybridEngineRouter by lazy { AniobHybridEngineRouter(modelsDir = modelDownloader.getModelsDir()) }

    /**
     * Durable knowledge vault (UX-2). Replaces InMemorySharedKnowledgeStore, whose contents died
     * with the process — "remember this answer" was a lie before this.
     */
    private val persistentKnowledgeStore = com.aniob.app.data.DataStoreKnowledgeStore(app)
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore = persistentKnowledgeStore

    // Task ownership & generation guards
    private var activeTaskJob: kotlinx.coroutines.Job? = null
    @Volatile private var activeGeneration: Long = 0L

    // UX-3: cooperative pause; checked at the loop head and inside settle waits.
    @Volatile private var pauseRequested = false

    /** Convenience accessor so the UI can read safetyLevel without collecting uiState. */
    val safetyLevel: SafetyLevel get() = _uiState.value.safetyLevel

    /** Exposes the loaded skill list for the Skills screen. */
    fun getLoadedSkills(): List<com.aniob.core.skills.AniobSkill> =
        semanticSkillMatcher.getLoadedSkills()

    // UX-4: memoized approvals; the dispatch path consults this.
    private val taskApprovals = mutableSetOf<String>()
    private val taskBlockedFingerprints = mutableSetOf<String>()
    internal var disabledSkills: MutableSet<String> = mutableSetOf()

    /** Suspends until the user decides; the ONLY gate on a risky dispatch (UX-4). */
    private var pendingConfirmation: CompletableDeferred<ConfirmDecision>? = null

    // UX-4 audit trails
    private val blockedRecords = mutableListOf<BlockedRecord>()

    private val localLlmProvider: AniobLocalLlmProvider = AniobLiteRtProvider().apply {
        // Reflex 5: release the LiteRT engine on memory pressure, no LLM involved.
        app.onMemoryTrimListener = { _ ->
            try {
                this.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Binds the on-device engine to the currently installed model file. Without this the provider
     * would target a stale default path and every local step would degrade to a fabricated action.
     */
    private fun refreshLocalEngine() {
        val modelFile = modelDownloader.getDefaultModelId()
            ?.let { modelDownloader.getInstalledModelFile(it) }
            ?: return
        if (localLlmClient.isModelLoaded()) return
        localLlmClient = AniobLocalLlmClient.useEngineForModel(modelFile.absolutePath)
    }

    /** Counts full accessibility-tree captures; asserted to stay unchanged when the Tracker opens. */
    internal var screenCaptureCount: Int = 0
        private set

    fun getInstalledModelName(): String? {
        val id = modelDownloader.getDefaultModelId() ?: return null
        return AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id
    }

    /** Installed model size in whole GB, for actionable offline guidance. */
    private fun recommendedModelSizeGb(modelId: String): Int =
        AniobModelDownloader.ALL_MODELS.find { it.id == modelId }?.sizeGb?.toInt() ?: 2

    /** Richer routing rationale for the "Why this model?" dialog. */
    fun getModelChoiceReason(): String {
        val installed = getInstalledModelName() ?: "none installed"
        val telemetry = AniobDeviceTelemetry.getRealState(app)
        val batteryToken = if (telemetry.isCharging) "charging" else "on battery ${telemetry.batteryPercent}%"
        val mode = _uiState.value.autoRouterMode
        val decision = _uiState.value.lastRoutingReason
        return "Battery $batteryToken. Mode '$mode' selected '$installed' " +
            "(local ${telemetry.totalRamGb}GB RAM device; local route keeps prompts on-device, " +
            "cloud route escalates only when the local model is unavailable). " +
            "Last decision: $decision"
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
        val savedQueued = prefs.getString("queued_prompt", null)
        localFailCount = prefs.getInt("local_fail_count", 0)
        disabledSkills = (prefs.getStringSet("disabled_skills", emptySet()) ?: emptySet()).toMutableSet()
        executionRouter.setDisabledSkills(disabledSkills)

        _uiState.update {
            it.copy(
                omnirouteApiKey = savedApiKey,
                omnirouteModel = savedModel,
                autoRouterMode = savedMode,
                queuedPrompt = savedQueued,
                // UX-4: switches are REAL state, restored from disk — never placebo remembr.
                safetyLevel = SafetyLevel.entries.getOrElse(
                    prefs.getInt("safety_level", SafetyLevel.STANDARD.ordinal)
                ) { SafetyLevel.STANDARD },
                fastPathEnabled = prefs.getBoolean("fastpath_enabled", true),
                safetyGateEnabled = prefs.getBoolean("safety_gate_enabled", true),
                disabledSkills = disabledSkills.toSet()
            )
        }

        executionRouter.fastPathEnabled = _uiState.value.fastPathEnabled

        // UX-4: an OFF safety level is never silently retained — re-arm on every process start.
        if (_uiState.value.safetyLevel == SafetyLevel.OFF) {
            setSafetyLevel(SafetyLevel.STANDARD)
        }

        _isReadyToRender.value = true

        // UX-2: load remembered answers so "Remember this answer" survives process death.
        viewModelScope.launch {
            runCatching { persistentKnowledgeStore.hydrate() }
                .onFailure { app.eventLogger.warn("AniobViewModel", "Knowledge vault hydration failed: ${it.message}") }
        }

        // Collect session scores from Room database
        viewModelScope.launch {
            app.metricsCollector.getSessionScores().collect { scores ->
                _uiState.update { it.copy(sessionScores = scores) }
            }
        }

        // Cold-start hydration (finding #5): restore FastPath trajectories from Room into the
        // replay engine before the first task, so "zero-token replay" survives process death.
        viewModelScope.launch {
            runCatching { learningPipeline.hydrate() }
                .onSuccess { loaded ->
                    if (loaded > 0) {
                        app.eventLogger.info("AniobViewModel", "Hydrated $loaded FastPath trajectories from Room")
                    }
                }
                .onFailure { app.eventLogger.warn("AniobViewModel", "FastPath hydration failed: ${it.message}") }
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

        // UX-6: a marker still present at cold start means the OS killed us mid-task. Report it
        // honestly instead of letting the run look like it silently succeeded.
        restoreInterruptedSummary()

        // UX-6: if the accessibility service dies while a task is active, surface the recovery door.
        AniobAccessibilityService.onServiceLost = { wasTaskActive ->
            if (wasTaskActive) {
                AniobBackgroundController.onAccessibilityLost(app)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        statusMessage = "Aniob lost accessibility access",
                        accessibilityRecoveryNeeded = true
                    )
                }
            }
        }

        AniobForegroundService.onStopRequestedFromNotification = {
            stopCurrentTask()
        }
        AniobForegroundService.onPauseToggleFromNotification = {
            togglePause()
        }
        AniobForegroundService.onConfirmDecisionWithIds = { decision, taskId, reqId ->
            if (!taskId.isNullOrBlank() && !reqId.isNullOrBlank()) {
                resolveConfirmation(decision, taskId, reqId)
            } else {
                app.eventLogger.warn("AniobViewModel", "Ignored ID-less confirmation from notification")
            }
        }

        viewModelScope.launch {
            app.approvalController.confirmRequest.collect { req ->
                _uiState.update { it.copy(confirmRequest = req) }
            }
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
    }

    fun dismissInterruptedSummary() {
        _uiState.update { it.copy(interruptedSummary = null) }
    }

    fun dismissRecoveryBanner() {
        _uiState.update { it.copy(accessibilityRecoveryNeeded = false) }
    }

    fun clearLastSummary() {
        _uiState.update { it.copy(lastSummary = null) }
    }

    fun setTrackerFilter(filter: String) {
        _uiState.update { it.copy(trackerFilter = filter) }
    }

    fun setShowTrackerSheet(show: Boolean) {
        // Tracker is a pure view over already-recorded Room/EventLogger data: opening it must not
        // trigger a screen capture or a model call (asserted by TrackerZeroCaptureTest).
        if (show) trackerOpenCount++
        _uiState.update { it.copy(showTrackerSheet = show) }
    }

    /** Test/diagnostic surface for the zero-capture guarantee. */
    fun trackerOpenCount(): Int = trackerOpenCount

    /** Renders only the newest [CHAT_RENDER_WINDOW] messages; the data list keeps everything (U18). */
    fun renderWindow(): List<ChatMessage> {
        val messages = _uiState.value.chatMessages
        return if (messages.size <= CHAT_RENDER_WINDOW) messages
        else messages.takeLast(CHAT_RENDER_WINDOW)
    }

    /**
     * Builds an honest completion card. Evidence comes straight from the verifier's unmet list,
     * never invented; a STOPPED outcome is never reported as SUCCESS or FAILED.
     */
    internal fun buildSummary(
        prompt: String,
        outcome: Outcome,
        steps: Int,
        durationMs: Long,
        provider: String,
        evidence: List<EvidenceItem>,
        reason: String? = null
    ): TaskSummary {
        val next = when (outcome) {
            Outcome.SUCCESS -> listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS)
            Outcome.FAILED -> listOf(NextAction.RETRY, NextAction.EXPLORE_APP, NextAction.VIEW_STEPS)
            Outcome.UNVERIFIED -> listOf(NextAction.RETRY, NextAction.VIEW_STEPS)
            Outcome.STOPPED -> listOf(NextAction.VIEW_STEPS)
            Outcome.INTERRUPTED -> listOf(NextAction.VIEW_STEPS)
        }
        return TaskSummary(
            prompt = prompt,
            outcome = outcome,
            steps = steps,
            durationMs = durationMs,
            provider = provider,
            evidence = evidence,
            nextActions = next,
            reason = reason
        )
    }

    /** Persists active task marker in Room so process death can be honestly reviewed on next open. */
    private fun persistRunningMarker(task: AniobTask, stepIndex: Int, lastKnownEffect: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.activeTaskDao().setActiveTask(
                    com.aniob.app.db.ActiveTaskEntity(
                        taskId = task.id,
                        rawPrompt = task.rawPrompt,
                        currentStep = stepIndex,
                        lastKnownEffect = lastKnownEffect,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun clearRunningMarker() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.activeTaskDao().clearActiveTasks()
            } catch (_: Exception) {}
        }
    }

    /** Reads the persisted marker on cold start and surfaces it as an Interrupted card. */
    private fun restoreInterruptedSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            // Check workflow repository first
            val recoveredList = try {
                app.workflowRepository.recoverAllOnStartup()
            } catch (_: Exception) { emptyList() }

            val interrupted = recoveredList.firstOrNull { it.state == "EffectUnknown" }
            if (interrupted != null) {
                val summary = buildSummary(
                    prompt = "Workflow ${interrupted.taskId}",
                    outcome = Outcome.INTERRUPTED,
                    steps = 1,
                    durationMs = 0L,
                    provider = "WORKFLOW",
                    evidence = emptyList(),
                    reason = "Interrupted during dispatch. Outcome in external app is unverified."
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(interruptedSummary = summary, isRunning = false) }
                }
            }

            val waiting = recoveredList.firstOrNull { it.state == "WaitingForUser" }
            if (waiting != null) {
                val dest = com.aniob.core.workflow.Destination(
                    handler = waiting.destinationKey,
                    url = waiting.destinationUrl,
                    category = com.aniob.core.workflow.DestinationCategory.WEBSITE
                )
                val identity = com.aniob.core.workflow.OperationIdentity(
                    taskId = waiting.taskId,
                    actionId = waiting.actionId,
                    generation = waiting.generation,
                    destinationKey = waiting.destinationKey,
                    approvedPayloadHash = waiting.payloadHash
                )
                app.workflowCoordinator.updateState(
                    com.aniob.core.workflow.WorkflowState.WaitingForUser(
                        identity = identity,
                        destination = dest,
                        brief = waiting.evidence
                    )
                )
            }

            if (interrupted != null) return@launch

            val marker = try {
                app.database.activeTaskDao().getActiveTask()
            } catch (_: Exception) { null } ?: return@launch

            val prompt = marker.rawPrompt
            val step = marker.currentStep
            val lastEffect = marker.lastKnownEffect
            val summary = buildSummary(
                prompt = prompt,
                outcome = Outcome.INTERRUPTED,
                steps = step,
                durationMs = 0L,
                provider = "NONE",
                evidence = emptyList(),
                reason = if (lastEffect.isNotBlank()) {
                    "Interrupted at step $step. Last known effect: $lastEffect. Review external effects before retrying."
                } else {
                    "Interrupted at step $step. Review external effects before retrying."
                }
            )
            clearRunningMarker()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _uiState.update { it.copy(interruptedSummary = summary, isRunning = false) }
            }
        }
    }

    // =========================================================================================
    // UX-2 Smart Composer
    // =========================================================================================

    /** Queues a follow-up while a task runs (depth 1; single-owner queue). */
    fun queueFollowUp(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putString("queued_prompt", trimmed).apply()
        _uiState.update { it.copy(queuedPrompt = trimmed) }
    }

    fun cancelQueuedFollowUp() {
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().remove("queued_prompt").apply()
        _uiState.update { it.copy(queuedPrompt = null) }
    }

    fun startQueuedTask() {
        val nextPrompt = _uiState.value.queuedPrompt ?: return
        cancelQueuedFollowUp()
        submitTask(nextPrompt)
    }

    /** Remember a grill answer in the durable vault (previously in-memory only). */
    fun rememberGrillAnswer(key: String, value: String) {
        persistentKnowledgeStore.put(vaultKey(key), value)
        _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys + key) }
    }

    fun forgetGrillAnswer(key: String) {
        persistentKnowledgeStore.remove(vaultKey(key))
        _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys - key) }
    }

    fun grillRemembered(key: String): String? = sharedKnowledgeStore.get(vaultKey(key))

    private fun vaultKey(questionId: String) = "grill.$questionId"

    /** UX-5: the "What Aniob remembers" list, surfaced from the Trust Center / Skills screen. */
    fun vaultEntries(): Map<String, String> =
        sharedKnowledgeStore.getAll().filterKeys { it.startsWith("grill.") }

    fun forgetVaultEntry(key: String) {
        persistentKnowledgeStore.remove(key)
    }

    private fun postMomentChip(label: String, kind: MomentChip.Kind) {
        val chip = MomentChip(id = "chip_${System.currentTimeMillis()}", label = label, kind = kind)
        _uiState.update { it.copy(momentChips = it.momentChips + chip) }
    }

    fun clearMomentChips() {
        _uiState.update { it.copy(momentChips = emptyList()) }
    }

    // =========================================================================================
    // UX-3 Glass Cockpit — narration, pause, takeover, stop budget
    // =========================================================================================

    /**
     * Publishes the frozen [StepEvent] for a step and mirrors the narration into every surface
     * (ticker, tracker, pill, notification). UI code touches only the contract, so the eventual
     * pipeline cutover is invisible here.
     *
     * `// SHIM(UX-3): replace with the StepPipeline feed when the cutover lands.`
     */
    internal fun publishStep(
        stepIndex: Int,
        action: AniobAction,
        provider: String,
        latencyMs: Long,
        verified: Boolean,
        failureReason: String? = null,
        node: AniobNode? = null
    ): StepEvent {
        val narration = StepNarration.narrate(action, node)
        val event = StepEvent(
            stepIndex = stepIndex,
            narration = narration,
            status = when {
                failureReason != null -> StepEvent.Status.FAILED
                verified -> StepEvent.Status.OK
                else -> StepEvent.Status.SKIPPED
            },
            provider = provider,
            latencyMs = latencyMs,
            failureReason = failureReason,
            technical = "${action.toolName} · ${provider.lowercase()}"
        )
        AniobExecutionEvents.publish(event)
        AniobBackgroundController.onStepNarrated(stepIndex, narration)
        _uiState.update {
            it.copy(stepEvents = it.stepEvents + event, lastNarration = narration)
        }
        return event
    }

    /** UX-3: cooperative pause. Resume re-captures a fresh screen; never resumes on a stale tree. */
    fun togglePause() {
        pauseRequested = !pauseRequested
        AniobBackgroundController.onTaskPaused(pauseRequested)
        _uiState.update {
            it.copy(
                isPaused = pauseRequested,
                statusMessage = if (pauseRequested) "Paused — do this step yourself, then Resume"
                else "Resuming with a fresh screen…"
            )
        }
    }

    /** Awaited at the loop head and inside settle waits so pause is honoured promptly. */
    internal suspend fun awaitIfPaused() {
        while (pauseRequested && _uiState.value.isRunning) {
            delay(PAUSE_POLL_MS)
        }
    }

    /**
     * Takeover Bridge (UX-3 §4): manual step performed by user during pause.
     * Resume cleanly without claiming an unconsented recording session has started.
     */
    fun onTakeoverCompleted() {
        _uiState.update {
            it.copy(
                statusMessage = "Manual step acknowledged. Ready to resume."
            )
        }
    }

    // =========================================================================================
    // UX-4 Trust & Safety
    // =========================================================================================

    fun setSafetyLevel(level: SafetyLevel) {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("safety_level", level.ordinal)
            .putBoolean("safety_gate_enabled", level != SafetyLevel.OFF)
            .apply()
        _uiState.update { it.copy(safetyLevel = level, safetyGateEnabled = level != SafetyLevel.OFF) }
    }

    fun isFastPathEnabled(): Boolean = _uiState.value.fastPathEnabled

    /** REAL switch: OFF gates the replay lookup — proven by a zero-replay-hits test. */
    fun setFastPathEnabled(enabled: Boolean) {
        executionRouter.fastPathEnabled = enabled
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("fastpath_enabled", enabled).apply()
        _uiState.update { it.copy(fastPathEnabled = enabled) }
    }

    fun isSafetyGateEnabled(): Boolean = _uiState.value.safetyGateEnabled

    /** Maps onto the safety level, so the switch can never diverge from the real gate. */
    fun setSafetyGateEnabled(enabled: Boolean) {
        setSafetyLevel(if (enabled) SafetyLevel.STANDARD else SafetyLevel.OFF)
    }

    fun blockedActions(): List<BlockedRecord> = blockedRecords.toList()

    fun clearBlockedFingerprint(fingerprint: String) {
        com.aniob.core.safety.AniobSafetyInterceptor.unblockFingerprint(fingerprint)
        taskBlockedFingerprints.remove(fingerprint)
        blockedRecords.removeAll { it.fingerprint == fingerprint }
        _uiState.update { it.copy(blockedActions = blockedRecords.toList()) }
    }

    fun confirmationHistory(): List<ConfirmationRecord> = _uiState.value.confirmationHistory

    private fun recordConfirmation(request: ConfirmRequest, decision: ConfirmDecision) {
        val record = ConfirmationRecord(
            what = request.what,
            risk = request.risk,
            decision = decision,
            at = System.currentTimeMillis()
        )
        _uiState.update {
            it.copy(confirmationHistory = (listOf(record) + it.confirmationHistory).take(CONFIRMATION_HISTORY_LIMIT))
        }
    }

    /**
     * The ONE gate on a risky dispatch (UX-4 §1). Replaces the old fire-and-forget
     * dialog: this suspends the caller until a real decision exists, so a risky
     * action can never execute while the approval is still on screen.
     */
    internal suspend fun requestConfirmation(request: ConfirmRequest): ConfirmDecision {
        val currentTask = _uiState.value.activeTask
        val identity = com.aniob.core.workflow.OperationIdentity(
            taskId = request.taskId.ifBlank { currentTask?.id ?: "" },
            actionId = request.id,
            generation = activeGeneration,
            destinationKey = request.target
        )
        _uiState.update { it.copy(confirmRequest = request) }
        AniobForegroundService.notifyConfirmation(app, request)
        val decision = app.approvalController.requestApproval(request, identity)
        recordConfirmation(request, decision)
        AniobForegroundService.clearConfirmation(app)
        _uiState.update { it.copy(confirmRequest = null) }
        return decision
    }

    /** Called by the ConfirmSheet / notification action with the user's real choice. */
    fun resolveConfirmation(decision: ConfirmDecision, taskId: String? = null, reqId: String? = null) {
        app.approvalController.resolveConfirmation(decision, taskId, reqId)
    }

    /**
     * Maps an interceptor verdict onto the frozen confirm contract.
     * The interceptor decides *whether* to ask; the user decides *whether to proceed*.
     */
    private fun buildConfirmRequest(
        what: String,
        why: String,
        risk: ConfirmRequest.Risk,
        details: String,
        taskId: String = _uiState.value.activeTask?.id ?: "",
        payload: String = "",
        target: String = ""
    ) = ConfirmRequest(
        id = "confirm_${System.currentTimeMillis()}",
        title = "Aniob needs your approval",
        what = what,
        why = why,
        risk = risk,
        details = details,
        taskId = taskId,
        payload = payload,
        target = target
    )

    private fun riskFromTier(tier: String, safetyLevel: SafetyLevel): ConfirmRequest.Risk = when {
        tier.equals("HIGH", true) -> ConfirmRequest.Risk.HIGH
        tier.equals("MEDIUM", true) -> ConfirmRequest.Risk.MEDIUM
        else -> ConfirmRequest.Risk.LOW
    }

    /**
     * True when this action needs an approval at the current safety level.
     * Hard required / HIGH tier safeguards cannot be disabled even if safetyLevel is OFF.
     */
    private fun needsApproval(tier: String, action: AniobAction, hardRequired: Boolean = false): Boolean {
        if (hardRequired || tier.equals("HIGH", true)) {
            return true
        }
        return when (_uiState.value.safetyLevel) {
            SafetyLevel.OFF -> false
            SafetyLevel.STRICT -> !tier.equals("LOW", true) || action is AniobAction.OpenApp
            SafetyLevel.STANDARD -> tier.equals("HIGH", true)
        }
    }

    // =========================================================================================
    // UX-5 Learning, visible
    // =========================================================================================

    /** Teach prefill handoff; the full Teach producer lands with the functional track. */
    fun startTeachFlow(prompt: String?) {
        val seed = prompt?.trim().orEmpty()
        _uiState.update {
            it.copy(
                chatMessages = it.chatMessages + ChatMessage(
                    id = "msg_teach_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = if (seed.isBlank()) {
                        "Tell me the task you want to teach, and I'll record the steps as a skill."
                    } else {
                        "Teach mode: I'll record the steps for '$seed' and save it as a skill."
                    },
                    badge = "Teach",
                    provider = "TEACH",
                    retryPrompt = seed.ifBlank { null }
                )
            )
        }
    }

    /** REAL switch: a disabled skill is skipped by the matcher (proven by test). */
    fun setSkillEnabled(name: String, enabled: Boolean) {
        val disabled = disabledSkills.toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        disabledSkills = disabled
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("disabled_skills", disabled).apply()
        executionRouter.setDisabledSkills(disabled)
        _uiState.update { it.copy(disabledSkills = disabled) }
    }

    fun deleteSkill(name: String) {
        // Learned skills are YAML files under the skill library; built-ins live in assets and are
        // not deletable, so a missing file is a no-op rather than an error.
        runCatching {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
            File(dir, "$name.yaml").takeIf { it.exists() }?.delete()
        }
    }

    // =========================================================================================
    // UX-6 survival helpers
    // =========================================================================================

    /** UX-6: last completed task as a card on next open (Quiet Return). */
    internal fun postSummaryCard(summary: TaskSummary) {
        _uiState.update { it.copy(lastSummary = summary) }
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

    fun handleIncomingShare(shared: com.aniob.app.workflow.ParsedIncomingContent) {
        val text = shared.text
        if (!text.isNullOrBlank()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > com.aniob.core.workflow.WorkflowLimits.TEXT_BRIEF_MAX_BYTES) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Shared text exceeds 64 KiB limit (${bytes.size} bytes). Cannot import.",
                        pendingInputPrefill = null
                    )
                }
            } else {
                _uiState.update { it.copy(pendingInputPrefill = text) }
            }
        }
    }

    fun consumePendingInputPrefill(): String? {
        val prefill = _uiState.value.pendingInputPrefill
        if (prefill != null) {
            _uiState.update { it.copy(pendingInputPrefill = null) }
        }
        return prefill
    }

    internal fun executeOwnedWorkflowAction(
        taskId: String,
        leaseGen: Long,
        prompt: String,
        action: com.aniob.core.workflow.WorkflowAction
    ) {
        val startTime = System.currentTimeMillis()
        val destName = when (action) {
            is com.aniob.core.workflow.WorkflowAction.OpenWebsite -> action.destination.handler
            is com.aniob.core.workflow.WorkflowAction.OpenApp -> action.destination.handler
            else -> "destination"
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            val currentJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
            app.taskOwner.attachJob(leaseGen, currentJob)

            _uiState.update {
                it.copy(
                    isRunning = true,
                    statusMessage = "Opening $destName...",
                    lastProviderUsed = "WORKFLOW"
                )
            }

            val result = app.workflowCoordinator.executeAction(action, taskId, leaseGen)
            when (result) {
                is com.aniob.core.workflow.WorkflowResult.WaitingForUser -> {
                    AniobForegroundService.notifyWaitingForUser(app, destName)
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            statusMessage = "Task waiting on $destName",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.LaunchAccepted -> {
                    app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.SUCCESS,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = listOf(EvidenceItem(label = "Opened $destName", met = true))
                    )
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            lastSummary = summary,
                            statusMessage = "Action launched: $destName",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.Failed -> {
                    app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.FAILED,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = emptyList(),
                        reason = result.errorReason
                    )
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            lastSummary = summary,
                            statusMessage = "Action failed: ${result.errorReason}",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.Cancelled -> {
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.STOPPED,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = emptyList(),
                        reason = result.reason
                    )
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            lastSummary = summary,
                            statusMessage = "Cancelled by user",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                else -> {
                    _uiState.update { it.copy(isRunning = false) }
                }
            }
        }
    }

    fun onDestinationChosen(destination: com.aniob.core.workflow.Destination, brief: String?) {
        val taskId = "task_chooser_${System.currentTimeMillis()}"
        val leaseGen = app.taskOwner.acquireLease(taskId)
        val action = com.aniob.core.workflow.WorkflowAction.OpenWebsite(
            actionId = "action_${System.currentTimeMillis()}",
            destination = destination,
            briefText = brief
        )
        executeOwnedWorkflowAction(
            taskId = taskId,
            leaseGen = leaseGen,
            prompt = "Open ${destination.handler}",
            action = action
        )
    }

    fun onContinueInWebsite(taskId: String) {
        val ws = app.workflowCoordinator.workflowState.value
        val waiting = (ws as? com.aniob.core.workflow.WorkflowState.WaitingForUser)?.takeIf { it.identity.taskId == taskId }
        if (waiting != null) {
            val leaseGen = app.taskOwner.acquireLease(taskId)
            val action = com.aniob.core.workflow.WorkflowAction.OpenWebsite(
                actionId = "action_continue_${System.currentTimeMillis()}",
                destination = waiting.destination,
                briefText = waiting.brief
            )
            executeOwnedWorkflowAction(
                taskId = taskId,
                leaseGen = leaseGen,
                prompt = "Continue in ${waiting.destination.handler}",
                action = action
            )
        }
    }

    fun onCustomUrlChosen(url: String, brief: String?) {
        val validated = com.aniob.core.workflow.WorkflowRouter.validateAndSanitizeUrl(url)
        if (validated == null) {
            _uiState.update { it.copy(statusMessage = "Invalid URL entered.") }
            return
        }
        val host = com.aniob.core.workflow.WorkflowRouter.extractUrl(validated)?.let {
            validated.substringAfter("://").substringBefore('/')
        } ?: validated
        val dest = com.aniob.core.workflow.Destination(
            handler = host,
            url = validated,
            category = com.aniob.core.workflow.DestinationCategory.WEBSITE,
            provenance = com.aniob.core.workflow.DestinationProvenance.USER_INPUT
        )
        onDestinationChosen(dest, brief)
    }

    fun onWorkflowMarkComplete(taskId: String, userNotes: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val result = app.workflowCoordinator.completeWaitingTask(taskId, userNotes)
            val summary = buildSummary(
                prompt = "Workflow $taskId",
                outcome = Outcome.SUCCESS,
                steps = 1,
                durationMs = 0L,
                provider = "WORKFLOW",
                evidence = if (userNotes.isNotBlank()) listOf(EvidenceItem(label = userNotes, met = true)) else emptyList()
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        lastSummary = summary,
                        statusMessage = "Workflow marked complete",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
        }
    }

    fun onWorkflowCancel(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = app.workflowCoordinator.cancelWorkflow(taskId)
            val summary = buildSummary(
                prompt = "Workflow $taskId",
                outcome = Outcome.STOPPED,
                steps = 1,
                durationMs = 0L,
                provider = "WORKFLOW",
                evidence = emptyList(),
                reason = "Cancelled by user"
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        lastSummary = summary,
                        statusMessage = "Workflow cancelled",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
        }
    }

    /**
     * Entry point when user submits a natural language command.
     */
    fun submitTask(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        // Reject if another task is active to prevent double submission
        if (_uiState.value.isRunning && activeTaskJob?.isActive == true) {
            app.eventLogger.warn("AniobViewModel", "Cannot start new task while a task is running")
            return
        }

        val taskId = "task_${System.currentTimeMillis()}"
        val leaseGen = app.taskOwner.acquireLease(taskId)
        val generation = ++activeGeneration
        val task = AniobTask(id = taskId, rawPrompt = trimmed)

        // Clear task-local state
        taskApprovals.clear()
        taskBlockedFingerprints.forEach { com.aniob.core.safety.AniobSafetyInterceptor.unblockFingerprint(it) }
        taskBlockedFingerprints.clear()
        pauseRequested = false
        localFailCount = 0
        verifiedActions.clear()

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
                steps = emptyList(),
                confirmRequest = null,
                pendingInputPrefill = null
            )
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            app.taskOwner.attachJob(leaseGen, currentCoroutineContext()[kotlinx.coroutines.Job]!!)

            // Route via WorkflowRouter before existing automation
            val workflowDecision = com.aniob.core.workflow.WorkflowRouter.route(trimmed)
            when (workflowDecision) {
                is com.aniob.core.workflow.WorkflowDecision.DirectAction -> {
                    executeOwnedWorkflowAction(
                        taskId = taskId,
                        leaseGen = leaseGen,
                        prompt = trimmed,
                        action = workflowDecision.action
                    )
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.NeedsChooser -> {
                    app.workflowCoordinator.updateState(
                        com.aniob.core.workflow.WorkflowState.NeedsChoice(workflowDecision.candidates, workflowDecision.prompt)
                    )
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            statusMessage = "Please choose a destination service"
                        )
                    }
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.NeedsClarification -> {
                    val asstMsg = ChatMessage(
                        id = "msg_clarify_${System.currentTimeMillis()}",
                        role = "assistant",
                        content = workflowDecision.question,
                        provider = "WORKFLOW"
                    )
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            chatMessages = it.chatMessages + asstMsg,
                            statusMessage = "Clarification needed"
                        )
                    }
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.DelegateToExisting -> {
                    // Fallthrough to existing automation / Q&A
                }
            }

            // Step 0: Pre-route supported shortcuts before general Q&A classification
            val resolvedShortcut = AniobIntentResolver.resolve(trimmed)
            val intent = if (resolvedShortcut != null) {
                AniobIntent.DEVICE_AUTOMATION
            } else {
                AniobTaskIntentClassifier.classify(trimmed)
            }

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
                        _uiState.update { it.copy(streamingBubbleText = it.streamingBubbleText + delta, isStreaming = true) }
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
                val tokenEstimate = if (result.provider == "VAULT" || result.provider == "NO_PROVIDER") 0
                    else (result.answer.length / 4).coerceAtLeast(1)
                app.metricsCollector.recordSession(
                    taskId = taskId,
                    prompt = trimmed,
                    isSuccess = result.provider != "NO_PROVIDER",
                    steps = 0,
                    durationMs = result.latencyMs,
                    tokensUsed = tokenEstimate,
                    providerUsed = result.provider,
                    decisionReason = "Direct intent answer for ${intent.name}"
                )
                return@launch
            }

            // Step 1: Grill-Me Check with remembered vault preferences
            val grillResult = grillMeEngine.evaluateTask(trimmed, sharedKnowledgeStore.getAll())
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

            // Step 1.5: Pre-flight Doctor Check
            if (!runDoctorPreflight(task, trimmed, resolvedShortcut)) {
                return@launch
            }

            // Task is self-contained -> Proceed directly
            executeTaskPipeline(task, generation)
        }
    }

    internal fun checkDoctorPreflight(prompt: String): com.aniob.core.tools.DoctorResult {
        val resolvedShortcut = AniobIntentResolver.resolve(prompt)
        return com.aniob.core.tools.AniobDoctor.preflightCheck(
            taskPrompt = prompt,
            targetPackage = resolvedShortcut?.targetPackage,
            isAccessibilityConnected = AniobAccessibilityService.isServiceConnected,
            modelsDir = modelDownloader.getModelsDir(),
            installedModelId = modelDownloader.getDefaultModelId(),
            isNetworkAvailable = AniobDeviceTelemetry.getRealState(app).isNetworkAvailable,
            autoRouterMode = _uiState.value.autoRouterMode,
            totalRamGb = AniobDeviceTelemetry.getRealState(app).totalRamGb,
            isAppInstalled = { pkg -> app.packageManager.getLaunchIntentForPackage(pkg) != null },
            isOfflineShortcut = (resolvedShortcut != null)
        )
    }

    private fun runDoctorPreflight(task: AniobTask, prompt: String, resolvedShortcut: ResolvedIntentShortcut?): Boolean {
        val doctorResult = checkDoctorPreflight(prompt)
        if (!doctorResult.allPassed) {
            val telemetry = AniobDeviceTelemetry.getRealState(app)
            val recommendedId = modelDownloader.getRecommendedModel(modelDownloader.getDeviceInfo())
            val recommendedName = AniobModelDownloader.ALL_MODELS.find { it.id == recommendedId }?.name ?: recommendedId
            val installedName = modelDownloader.getDefaultModelId()
                ?.let { id -> AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id }
            val failedChecksSummary = doctorResult.checks.filter { !it.passed }.joinToString { "${it.name}: ${it.reason}" }
            val tierInfo = " [Tier: ${doctorResult.readinessTier.name}]"
            val doctorErrorMsg = ChatMessage(
                id = "msg_doctor_${System.currentTimeMillis()}",
                role = "assistant",
                content = when {
                    doctorResult.checks.any { it.name == "network_available" && !it.passed } ->
                        "Offline — install an on-device model (${recommendedName}, ${recommendedModelSizeGb(recommendedId)}GB) " +
                            "in Models screen or check connection. Your device has ${telemetry.totalRamGb}GB RAM.$tierInfo Checks: $failedChecksSummary"
                    doctorResult.checks.any { it.name == "accessibility_connected" && !it.passed } ->
                        "Accessibility Service Disabled — Enable Aniob in Settings to automate apps.$tierInfo Checks: $failedChecksSummary"
                    doctorResult.checks.any { it.name == "model_file_exists" && !it.passed } ->
                        "Local model file missing — install ${installedName ?: recommendedName} in Models screen " +
                            "or switch to Auto mode.$tierInfo Checks: $failedChecksSummary"
                    else -> "Cannot start task: ${doctorResult.failReason}.$tierInfo Checks: $failedChecksSummary"
                },
                badge = "⚠️ Check Failed",
                provider = "DOCTOR",
                retryPrompt = prompt
            )
            _uiState.update {
                it.copy(
                    isRunning = false,
                    statusMessage = "System check failed",
                    chatMessages = it.chatMessages + doctorErrorMsg,
                    lastRoutingReason = doctorResult.failReason ?: "Doctor pre-flight failed"
                )
            }
            viewModelScope.launch {
                app.metricsCollector.recordSession(
                    taskId = task.id,
                    prompt = prompt,
                    isSuccess = false,
                    steps = 0,
                    durationMs = 0,
                    tokensUsed = 0,
                    providerUsed = "DOCTOR",
                    decisionReason = doctorResult.failReason ?: "Doctor pre-flight failed"
                )
            }
            return false
        }
        return true
    }

    fun onGrillAnswersSubmitted(answers: Map<String, String>, rememberKeys: Set<String> = emptySet()) {
        val currentTask = _uiState.value.activeTask ?: return
        // Persist the answers the user ticked so the question is skipped next time (UX-2).
        rememberKeys.forEach { key ->
            answers[key]?.let { rememberGrillAnswer(key, it) }
        }
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

        val gen = activeGeneration
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            val resolvedShortcut = AniobIntentResolver.resolve(clarifiedPrompt)
            if (!runDoctorPreflight(updatedTask, clarifiedPrompt, resolvedShortcut)) {
                return@launch
            }
            executeTaskPipeline(updatedTask, gen)
        }
    }

    /** Explicit cancel from the Grill sheet ("Cancel task"), not a silent dismiss. */
    fun cancelGrillMe() {
        _uiState.update { it.copy(showGrillMeSheet = false, isRunning = false, statusMessage = "Task cancelled") }
    }

    fun dismissGrillMe() = cancelGrillMe()

    /**
     * Executes the task through the ExecutionRouter ladder:
     * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
     */
    private suspend fun executeTaskPipeline(task: AniobTask, taskGeneration: Long = activeGeneration) {
        if (taskGeneration != activeGeneration) return
        persistRunningMarker(task, 0, "Task started")

        watchdog.reset()
        observationPolicy.reset()
        executionTracker.clear()
        refreshLocalEngine()

        // Honest completion (no fake SUCCESS): extract deterministic success criteria BEFORE step 0
        // so a provisional Finish can be verified against evidence. Without criteria a Finish could
        // be declared on an unchanged screen.
        val appCatalog = try {
            com.aniob.core.knowledge.AniobAppCatalog.getInstance().getAll()
                .filter { it.appName.isNotBlank() }
                .associate { entry ->
                    entry.appName.lowercase() to entry.packageName
                }
        } catch (_: Exception) {
            emptyMap()
        }
        val resolvedCriteria = task.successCriteria ?: AniobTaskIntentClassifier.extractCriteria(
            prompt = task.clarifiedGoal,
            grillAnswers = task.grillAnswers,
            appCatalog = appCatalog
        )
        var taskContext = com.aniob.core.domain.TaskContext(
            instruction = task.clarifiedGoal,
            successCriteria = resolvedCriteria,
            grillAnswers = task.grillAnswers,
            taskId = task.id
        )

        AniobAccessibilityService.isTaskActive = true
        val startTime = System.currentTimeMillis()
        var currentStep = 0
        var totalTokens = 0
        var primaryProvider = "INTENT"
        var decisionReason = "Direct intent shortcut"
        var screenReads = 0
        var actionsDispatched = 0
        var escalations = 0
        var finishRejections = 0
        verifiedActions.clear()

        // Prefrontal-cortex condensation: TaskProgress summary (not full history) is
        // threaded through the ladder so the LLM sees a pure-text progress summary.
        var taskProgress: TaskProgress? = null

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
            verified: Boolean,
            textEvidence: String? = null,
            targetBounds: AniobRect? = null
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
                    verified = verified,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
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
                    isVerified = verified,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
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
                    provider = provider,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
                )
            )
        }

        val stepPipe = StepPipeline(
            limits = agentLimits,
            learning = learningPipeline,
            reflectionAgent = reflectionAgent,
            recordStep = { stepRecord ->
                val node = stepRecord.resolvedNode
                val obs = if (node != null) {
                    "node ${node.id} (${node.text.take(15)})"
                } else {
                    "screen ${screenBefore?.treeHash?.take(6) ?: "none"} after ${stepRecord.action.toolName}"
                }
                recordTrajectory(
                    stepIndex = stepRecord.stepIndex,
                    observation = obs,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    screenHash = screenBefore?.treeHash ?: "",
                    verified = stepRecord.verified,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                publishStep(
                    stepIndex = stepRecord.stepIndex,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    verified = stepRecord.verified,
                    failureReason = stepRecord.failureReason,
                    node = stepRecord.resolvedNode
                )
                val uiStep = AniobStepRecord(
                    stepIndex = stepRecord.stepIndex,
                    screenHash = screenBefore?.treeHash ?: "",
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    tokensUsed = stepRecord.tokensUsed,
                    verifiedSuccess = stepRecord.verified,
                    failureReason = stepRecord.failureReason,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                addStepRecord(uiStep)
            },
            settle = { _, capture ->
                AniobWaitForIdle.waitForIdle(1000)
                delay(400)
                capture()
            }
        )

        val maxSteps = agentLimits.maxStepsPerTask
        var finalOutcome: Outcome? = null
        var terminalReason: String = ""
        var stepResult: StepResult? = null

        try {
            while (currentStep < maxSteps && _uiState.value.isRunning) {
                if (taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                    finalOutcome = Outcome.STOPPED
                    break
                }
                val wasPaused = pauseRequested
                awaitIfPaused()
                if (!_uiState.value.isRunning || taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                    finalOutcome = Outcome.STOPPED
                    break
                }

                _uiState.update { it.copy(currentStep = currentStep + 1) }

                // Check Observation Policy: burst if predictable, else full capture
                val captured = if (wasPaused || observationPolicy.shouldObserve() || screenBefore == null) {
                    screenReads++; screenCaptureCount++
                    val fresh = a11y?.captureCurrentScreenState() ?: AniobScreenState(
                        packageName = "com.aniob.app",
                        treeHash = "mock_hash_${currentStep}",
                        nodes = listOf(
                            AniobNode(id = 1, className = "TextView", text = "Settings", isClickable = true, bounds = AniobRect(50, 100, 300, 180)),
                            AniobNode(id = 2, className = "Button", text = "Search", isClickable = true, bounds = AniobRect(50, 200, 300, 280))
                        )
                    )
                    if (wasPaused && screenBefore != null && fresh.treeHash != screenBefore.treeHash) {
                        onTakeoverCompleted()
                    }
                    fresh
                } else {
                    screenBefore
                }

                // Session validation: do not trust a cached app session blindly.
                val foregroundBefore = a11y?.currentForegroundPackage()
                val reuseCheck = appSessionManager.ensureReusableOrInvalidate(
                    packageName = captured.packageName,
                    liveForegroundPackage = foregroundBefore,
                    liveScreenState = captured
                )
                var currentScreen = captured
                val liveService = a11y
                if (!reuseCheck.valid && liveService != null) {
                    if (foregroundBefore != null && foregroundBefore != captured.packageName) {
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Session invalid (${reuseCheck.reason}) - cold starting $foregroundBefore"
                        )
                        executeActionSync(liveService, AniobAction.OpenApp(packageName = foregroundBefore))
                        delay(800)
                        currentScreen = liveService.captureCurrentScreenState()
                        screenReads++; screenCaptureCount++
                    } else {
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Session tree stale (${reuseCheck.reason}) - refreshing capture"
                        )
                        currentScreen = liveService.captureCurrentScreenState()
                        screenReads++; screenCaptureCount++
                    }
                }
                appSessionManager.onSessionScreenUpdated(currentScreen.packageName, currentScreen)

                // Evaluate Execution Ladder with real power state and on-device model status
                val powerState = AniobDeviceTelemetry.getRealState(app)
                val resolvedModelId = modelDownloader.getDefaultModelId()
                val resolvedFileExists = File(modelDownloader.getModelsDir(), "$resolvedModelId.gguf").exists()
                val effectiveModelId = resolvedModelId.takeIf { resolvedFileExists }
                val isModelFileMissing = !resolvedFileExists

                if (currentStep == 0 && _uiState.value.fastPathEnabled) {
                    learningPipeline.awaitHydration()
                }

                val userRouting = _uiState.value.autoRouterMode.lowercase()
                val ladderResult = executionRouter.planStep(
                    taskPrompt = task.clarifiedGoal,
                    screenState = currentScreen,
                    stepIndex = currentStep,
                    taskSignature = task.rawPrompt.lowercase(),
                    powerState = powerState,
                    installedModelId = effectiveModelId,
                    lastLocalFailCount = localFailCount,
                    isModelFileMissing = isModelFileMissing,
                    previousProgress = taskProgress,
                    userRoutingMode = userRouting,
                    lastObservedResult = if (currentStep > 0) "Step ${currentStep - 1} observed" else null
                )
                if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch) {
                    taskProgress = ladderResult.progress ?: taskProgress
                }

                val proposal: StepPipeline.Proposal
                var targetNode: AniobNode? = null
                when (ladderResult) {
                    is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                        primaryProvider = "INTENT"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing direct system intent...") }
                        proposal = StepPipeline.Proposal.DirectIntent(
                            action = AniobAction.Finish(summary = "Intent dispatched: ${ladderResult.shortcut.targetPackage}"),
                            label = "INTENT"
                        )
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.FastPathStep -> {
                        primaryProvider = "FASTPATH"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "FastPath replay step ${currentStep + 1}...") }
                        proposal = StepPipeline.Proposal.FastPath(ladderResult.action)
                        targetNode = ladderResult.action.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                        }
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution -> {
                        primaryProvider = "SKILL"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing Skill '${ladderResult.skill.name}' step ${currentStep + 1}...") }
                        proposal = StepPipeline.Proposal.Skill(ladderResult.action, skillName = ladderResult.skill.name)
                        targetNode = ladderResult.action.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                        }
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.CannotProceed -> {
                        primaryProvider = "NONE"
                        decisionReason = ladderResult.reason
                        _uiState.update {
                            it.copy(
                                statusMessage = "Cannot proceed: ${ladderResult.reason}",
                                lastRoutingReason = ladderResult.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        proposal = StepPipeline.Proposal.Model(
                            AniobAction.Fail(ladderResult.reason),
                            primaryProvider
                        )
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch -> {
                        val decision = ladderResult.decision
                        primaryProvider = when (decision.target) {
                            RouteTarget.OMNIROUTE_CLOUD -> "OMNIROUTE_CLOUD"
                            RouteTarget.LOCAL_SLM -> "LOCAL_SLM"
                            else -> "NONE"
                        }
                        decisionReason = decision.reason
                        _uiState.update {
                            it.copy(
                                statusMessage = "Routed to $primaryProvider: ${decision.reason}",
                                lastRoutingReason = decision.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        if (decision.target == RouteTarget.OMNIROUTE_CLOUD) escalations++

                        val localCaps = if (decision.target == RouteTarget.LOCAL_SLM) localLlmClient.capabilities() else null
                        val localCanDrive = decision.target == RouteTarget.LOCAL_SLM &&
                            localLlmClient.isModelLoaded() && (localCaps?.canDriveActions == true)
                        if (decision.target == RouteTarget.LOCAL_SLM && !localCanDrive) {
                            app.eventLogger.warn("AniobViewModel", "Local engine quarantined: ${localCaps?.reason ?: "model not loaded"}")
                            decisionReason = "local_quarantined: ${localCaps?.reason ?: "model not loaded"}"
                            _uiState.update {
                                it.copy(lastProviderUsed = primaryProvider, lastRoutingReason = decisionReason)
                            }
                        }

                        val executorRequest = taskContext.buildExecutorRequest(
                            screen = currentScreen,
                            activeSubgoal = taskProgress?.summary,
                            lastAction = taskContext.trajectory.lastOrNull()?.describeAction(),
                            lastResult = taskContext.condensedHistory.lastOrNull()
                        )
                        val executorPrompt = executorRequest.toPromptString()
                        val baseSystemPrompt = configLoader.prompt("system_prompt.txt") ?: "System: Android Agent. Reply with a single JSON tool call."
                        val systemPrompt = if (baseSystemPrompt.contains("ACTION_JSON_SCHEMA")) {
                            baseSystemPrompt
                        } else {
                            "$baseSystemPrompt\n\nSchema:\n${com.aniob.core.domain.AniobActionSchema.ACTION_JSON_SCHEMA}"
                        }

                        val plannedAction = if (localCanDrive) {
                            val gen = localLlmClient.generateStepResult(
                                systemPrompt,
                                executorPrompt
                            )
                            totalTokens += 80
                            val structured = AniobLocalActionResolver.resolveStructured(gen, currentScreen)
                            if (structured.usedFallback) {
                                app.eventLogger.warn("AniobViewModel", "Local SLM produced no schema-valid action: ${structured.reason}")
                            }
                            structured.action
                        } else if (decision.target == RouteTarget.LOCAL_SLM) {
                            AniobAction.Fail("Local model is not capable or ready: ${localCaps?.reason ?: "model not loaded"}")
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD && _uiState.value.omnirouteApiKey.isNotBlank()) {
                            val omniroute = AniobOmniRouteProvider(
                                apiKey = _uiState.value.omnirouteApiKey,
                                model = _uiState.value.omnirouteModel
                            )
                            val needsVision = (localFailCount > 0) || task.rawPrompt.contains(Regex("(?i)look|see|inspect|read|image|photo|visual"))
                            val isSensitiveScreen = currentScreen.nodes.any {
                                it.isPassword || it.viewId.contains(Regex("(?i)password|pwd|otp|pin|cvv|card_number"))
                            }
                            val screenshotBase64 = if (needsVision && !isSensitiveScreen && a11y != null) {
                                val snap = a11y.captureScreenshotAsync()
                                snap.bitmap?.let { bmp ->
                                    val stream = java.io.ByteArrayOutputStream()
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                                    android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                }
                            } else null
                            val raw = omniroute.getNextActionRaw(systemPrompt, executorPrompt, screenshotBase64)
                            totalTokens += 150
                            raw.fold(
                                onSuccess = { text ->
                                    var structured = AniobStructuredOutput.parseOrRepair(
                                        raw = text,
                                        screenState = currentScreen,
                                        decode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.PLANNER)
                                    )
                                    if (structured.usedFallback) {
                                        val repairMsg = "${AniobStructuredOutput.repairPrompt(text)}\n\nOriginal Request:\n$executorPrompt"
                                        val repaired = runCatching {
                                            omniroute.getNextActionRaw(
                                                systemPrompt,
                                                repairMsg,
                                                screenshotBase64
                                            ).getOrNull()
                                        }.getOrNull()
                                        if (repaired != null) {
                                            structured = AniobStructuredOutput.parseOrRepair(
                                                raw = repaired,
                                                screenState = currentScreen,
                                                decode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.PLANNER)
                                            )
                                        }
                                    }
                                    if (structured.usedFallback) {
                                        app.eventLogger.warn("AniobViewModel", "Cloud output unusable: ${structured.reason}")
                                    }
                                    structured.action
                                },
                                onFailure = { error ->
                                    AniobAction.Fail("Cloud action generation failed: ${error.message ?: "network error"}")
                                }
                            )
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD) {
                            AniobAction.Fail("OmniRoute cloud API key is missing")
                        } else {
                            AniobAction.Fail("No capable provider available: ${decision.reason}")
                        }

                        // Watchdog check
                        watchdog.record(currentScreen.treeHash, plannedAction)
                        val loopDetected = watchdog.isLoopDetected()
                        val loopAction = if (loopDetected) {
                            watchdog.reset()
                            AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK)
                        } else {
                            plannedAction
                        }

                        val targetSom = loopAction.semanticTarget() as? SemanticTarget.SomIndex
                        val effectiveAction = if (targetSom != null && currentScreen.nodes.none { it.id == targetSom.index }) {
                            AniobAction.Fail("Node id ${targetSom.index} not present on current screen")
                        } else {
                            loopAction
                        }

                        proposal = StepPipeline.Proposal.Model(effectiveAction, primaryProvider)
                        targetNode = effectiveAction.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(effectiveAction, currentScreen)?.node
                        }
                    }
                }

                persistRunningMarker(task, currentStep + 1, proposal.action.describeAction())

                val captureLambda: suspend () -> AniobScreenState = {
                    screenReads++; screenCaptureCount++
                    a11y?.captureCurrentScreenState() ?: currentScreen
                }

                val dispatchLambda: suspend (AniobAction) -> StepPipeline.DispatchOutcome = { act ->
                    when (act) {
                        is AniobAction.Finish -> {
                            StepPipeline.DispatchOutcome(dispatched = true, reason = act.summary)
                        }
                        is AniobAction.Fail -> {
                            StepPipeline.DispatchOutcome(dispatched = false, reason = act.reason)
                        }
                        else -> {
                            if (proposal is StepPipeline.Proposal.DirectIntent && ladderResult is AniobExecutionRouter.ExecutionPlanResult.DirectIntent) {
                                val ok = dispatchSystemIntent(ladderResult.shortcut)
                                AniobWaitForIdle.waitForIdle(800)
                                val screenAfter = a11y?.captureCurrentScreenState()
                                actionsDispatched++
                                StepPipeline.DispatchOutcome(dispatched = ok, screenAfter = screenAfter)
                            } else {
                                val ok = if (a11y != null) executeActionSync(a11y, act) else true
                                actionsDispatched++
                                AniobWaitForIdle.waitForIdle(600)
                                val screenAfter = a11y?.captureCurrentScreenState()
                                StepPipeline.DispatchOutcome(dispatched = ok, screenAfter = screenAfter)
                            }
                        }
                    }
                }

                val confirmLambda: suspend (String) -> Boolean = { actionDesc ->
                    val confirmReq = buildConfirmRequest(
                        what = proposal.action.toString().take(80),
                        why = "Action requires user confirmation",
                        risk = ConfirmRequest.Risk.HIGH,
                        details = actionDesc,
                        taskId = task.id,
                        payload = proposal.action.toString(),
                        target = targetNode?.text?.take(40) ?: ""
                    )
                    val decision = requestConfirmation(confirmReq)
                    decision == ConfirmDecision.APPROVE_ONCE || decision == ConfirmDecision.APPROVE_FOR_TASK
                }

                val res = stepPipe.executeStep(
                    ctx = taskContext,
                    planningScreen = currentScreen,
                    proposal = proposal,
                    capture = captureLambda,
                    dispatch = dispatchLambda,
                    confirm = confirmLambda,
                    targetNode = targetNode
                )
                stepResult = res
                taskContext = res.nextCtx
                observationPolicy.recordActionOutcome(proposal.action, currentScreen.packageName, res.verification?.isSuccessful == true)

                if (primaryProvider == "LOCAL_SLM") {
                    if (res.verification?.isSuccessful == true) localFailCount = 0 else localFailCount++
                    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("local_fail_count", localFailCount)
                        .apply()
                }

                if (res.terminalState == StepResult.TerminalState.SUCCESS) {
                    finalOutcome = Outcome.SUCCESS
                    terminalReason = res.verification?.reason ?: "Verified successfully"
                    break
                } else if (res.terminalState == StepResult.TerminalState.FAIL) {
                    finalOutcome = Outcome.FAILED
                    terminalReason = res.verification?.reason ?: "Step failed or rejected"
                    break
                }

                screenBefore = res.nextCtx.trajectory.lastOrNull()?.let { currentScreen }
                currentStep++
            }
        } finally {
            AniobAccessibilityService.isTaskActive = false
            learningPipeline.onTaskEnd(task.id)
        }

        if (taskGeneration != activeGeneration) {
            return
        }

        val finalScreen = a11y?.captureCurrentScreenState() ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
        val criteriaEvaluations = resolvedCriteria?.evaluateCriteria(finalScreen, currentStep) ?: emptyList()
        val evidenceItems = criteriaEvaluations.map { ev ->
            EvidenceItem(
                label = ev.description,
                met = ev.state == com.aniob.core.domain.CriterionState.PASSED,
                detail = ev.detail,
                state = ev.state
            )
        }

        if (finalOutcome == null) {
            finalOutcome = when {
                !_uiState.value.isRunning -> Outcome.STOPPED
                currentStep >= maxSteps -> {
                    terminalReason = "Maximum steps ($maxSteps) reached"
                    Outcome.FAILED
                }
                stepResult?.terminalState == StepResult.TerminalState.SUCCESS -> {
                    if (resolvedCriteria.hasObservableCheck) {
                        val anyFailed = criteriaEvaluations.any { it.state == com.aniob.core.domain.CriterionState.FAILED }
                        if (anyFailed) Outcome.FAILED else Outcome.SUCCESS
                    } else {
                        Outcome.UNVERIFIED
                    }
                }
                stepResult?.terminalState == StepResult.TerminalState.FAIL -> Outcome.FAILED
                else -> Outcome.FAILED
            }
        } else if (finalOutcome == Outcome.SUCCESS && !resolvedCriteria.hasObservableCheck) {
            finalOutcome = Outcome.UNVERIFIED
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val isSuccess = (finalOutcome == Outcome.SUCCESS)
        val resultSummary = when (finalOutcome) {
            Outcome.SUCCESS -> "Task completed in ${totalDuration}ms (${currentStep + 1} steps)"
            Outcome.UNVERIFIED -> "Task finished (${currentStep + 1} steps), but outcome could not be verified"
            Outcome.STOPPED -> "Task stopped"
            Outcome.INTERRUPTED -> "Task interrupted"
            Outcome.FAILED -> "Task terminated: ${terminalReason.ifBlank { "Unmet criteria" }}"
        }

        val consolidatedSkill = if (isSuccess) {
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

        val execOutcome = when (finalOutcome) {
            Outcome.SUCCESS -> ExecOutcome.SUCCESS
            Outcome.STOPPED -> ExecOutcome.STOPPED
            Outcome.INTERRUPTED -> ExecOutcome.INTERRUPTED
            Outcome.UNVERIFIED -> ExecOutcome.UNVERIFIED
            Outcome.FAILED -> ExecOutcome.FAILED_VERIFICATION
        }
        ExecReport(
            outcome = execOutcome,
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

        val summaryCard = buildSummary(
            prompt = task.rawPrompt,
            outcome = finalOutcome,
            steps = currentStep + 1,
            durationMs = totalDuration,
            provider = primaryProvider,
            evidence = evidenceItems,
            reason = terminalReason.ifBlank { null }
        )
        postSummaryCard(summaryCard)
        clearRunningMarker()

        AniobBackgroundController.onTaskFinished(app, resultSummary, isSuccess = isSuccess)

        val chatBadge = when (finalOutcome) {
            Outcome.SUCCESS -> "Success"
            Outcome.UNVERIFIED -> "Unverified"
            Outcome.STOPPED -> "Stopped"
            Outcome.INTERRUPTED -> "Interrupted"
            Outcome.FAILED -> "Failed"
        }
        val assistantMessage = ChatMessage(
            id = "msg_asst_${System.currentTimeMillis()}",
            role = "assistant",
            content = when (finalOutcome) {
                Outcome.SUCCESS -> "Completed '${task.rawPrompt}' via $primaryProvider. Verified: ${evidenceItems.filter { it.met }.joinToString { it.label }.ifBlank { "All criteria" }}. $resultSummary"
                Outcome.UNVERIFIED -> "Executed '${task.rawPrompt}' via $primaryProvider, but completion could not be verified automatically (${terminalReason.ifBlank { "No observable criteria" }}). Needs manual review."
                Outcome.STOPPED -> "Stopped '${task.rawPrompt}'. Earlier changes were not undone."
                Outcome.INTERRUPTED -> "Interrupted '${task.rawPrompt}'."
                Outcome.FAILED -> "Failed '${task.rawPrompt}': ${terminalReason.ifBlank { "Unmet criteria" }}. Left unverified: ${evidenceItems.filter { !it.met }.joinToString { it.label }.ifBlank { "All criteria" }}."
            },
            badge = chatBadge,
            stepIndex = currentStep + 1
        )

        viewModelScope.launch {
            app.metricsCollector.recordSession(
                taskId = task.id,
                prompt = task.rawPrompt,
                isSuccess = isSuccess,
                steps = currentStep + 1,
                durationMs = totalDuration,
                tokensUsed = totalTokens,
                providerUsed = primaryProvider,
                decisionReason = decisionReason
            )
            if (isSuccess) {
                val trajectory = verifiedActions.toList()
                learningPipeline.onTaskSuccess(
                    TaskContext(
                        instruction = task.rawPrompt,
                        successCriteria = resolvedCriteria,
                        taskId = task.id
                    ),
                    trajectory
                )
            }
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
        if (step.verifiedSuccess) verifiedActions.add(step.action)
        _uiState.update { it.copy(steps = it.steps + step) }
    }

    /** Actions of steps that verified, in order — the input to the learning pipeline. */
    private val verifiedActions = mutableListOf<AniobAction>()

    /**
     * Evidence gate for a provisional `Finish`.
     *
     * Shared by every ladder branch so none of them can declare success on an unverified screen.
     * An empty criteria set carries no evidence, so it is reported unverified — the caller then
     * treats the Finish as rejected rather than silently accepting it.
     */
    private fun verifyProvisionalFinish(
        finish: AniobAction.Finish,
        finalScreen: AniobScreenState,
        steps: Int,
        criteria: SuccessCriteria?
    ): DeterministicVerifier.VerificationResult =
        DeterministicVerifier.verifyFinish(criteria, finalScreen, steps)

    private suspend fun executeActionSync(a11y: AniobAccessibilityService?, action: AniobAction): Boolean {
        // Block until the UI is quiescent so we never dispatch into a running animation.
        AniobWaitForIdle.waitForIdle(1000)
        if (a11y == null) return false
        val deferred = CompletableDeferred<Boolean>()
        a11y.executeAction(action) { success ->
            deferred.complete(success)
        }
        return withTimeoutOrNull(SWIPE_CALLBACK_TIMEOUT_SECONDS * 1000L) { deferred.await() } ?: false
    }

    private data class VerifiedStep(
        val screenAfter: AniobScreenState,
        val executedAction: AniobAction,
        val verification: DeterministicVerifier.VerificationResult,
        val scrollRecovered: Boolean
    )

    /**
     * Executes an action, verifies it, and - when the target appears to have been off-screen -
     * scrolls the scrollable container to reveal it and retries once. This is what stops the
     * long-list failure mode where a valid element below the fold is reported as not found.
     */
    private suspend fun executeVerifyAndRecover(
        a11y: AniobAccessibilityService?,
        action: AniobAction,
        taskPrompt: String,
        currentScreen: AniobScreenState
    ): VerifiedStep {
        executeActionSync(a11y, action)
        delay(600)
        val screenAfter = a11y?.captureCurrentScreenState() ?: currentScreen
        val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)

        val recoverable = action.semanticTarget() != null && isTargetLikelyMissing(verification)
        if (!recoverable) return VerifiedStep(screenAfter, action, verification, false)

        return scrollRecoverAndRetry(a11y, action, taskPrompt, currentScreen)
            ?: VerifiedStep(screenAfter, action, verification, false)
    }

    private fun isTargetLikelyMissing(verification: DeterministicVerifier.VerificationResult): Boolean {
        if (verification.isSuccessful) return false
        val reason = verification.reason.lowercase()
        return reason.contains("no-effect") || reason.contains("no effect") ||
            reason.contains("not found") || reason.contains("element") ||
            reason.contains("observable effect") || reason.contains("failed")
    }

    private suspend fun scrollRecoverAndRetry(
        a11y: AniobAccessibilityService?,
        action: AniobAction,
        taskPrompt: String,
        currentScreen: AniobScreenState
    ): VerifiedStep? {
        val service = a11y ?: return null
        val keywords = promptKeywords(taskPrompt)

        fun matches(node: AniobNode): Boolean {
            val target = action.semanticTarget()
            if (target is SemanticTarget.SomIndex && node.id == target.index) return true
            return keywords.any { kw ->
                node.text.contains(kw, ignoreCase = true) ||
                    node.contentDescription.contains(kw, ignoreCase = true)
            }
        }

        val scrollResult = AniobScrollHelper.scrollUntilFound(
            screenState = currentScreen,
            targetPredicate = ::matches,
            taskPrompt = taskPrompt,
            capture = { service.captureCurrentScreenState() },
            executeSwipe = { swipe -> executeActionBlocking(service, swipe) }
        )
        if (!scrollResult.found) return null

        // Re-capture so node ids line up with the scrolled layout, then locate the target again.
        val revealedScreen = service.captureCurrentScreenState()
        val revealedNode = revealedScreen.nodes.firstOrNull(::matches) ?: scrollResult.node ?: return null
        val revealedTarget = SemanticTarget.SomIndex(revealedNode.id)
        val retryAction = when (action) {
            is AniobAction.InputText -> action.copy(target = revealedTarget)
            is AniobAction.LongPress -> action.copy(target = revealedTarget)
            else -> AniobAction.Tap(target = revealedTarget, thought = action.thought)
        }

        app.eventLogger.info(
            "AniobViewModel",
            "Scroll-until-found revealed node ${revealedNode.id} after ${scrollResult.swipes} swipes"
        )
        executeActionSync(a11y, retryAction)
        delay(600)
        val screenAfter = service.captureCurrentScreenState()
        val verification = DeterministicVerifier.verify(retryAction, revealedScreen, screenAfter)
        return VerifiedStep(screenAfter, retryAction, verification, true)
    }

    private fun executeActionBlocking(service: AniobAccessibilityService, action: AniobAction): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        service.executeAction(action) { result ->
            success = result
            latch.countDown()
        }
        latch.await(SWIPE_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return success
    }

    private fun promptKeywords(taskPrompt: String): List<String> =
        taskPrompt.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in SCROLL_STOPWORDS }
            .distinct()

    private fun dispatchSystemIntent(shortcut: ResolvedIntentShortcut): Boolean {
        val systemAction = shortcut.extras[AniobIntentResolver.EXTRA_SYSTEM_ACTION]
        return try {
            when (systemAction) {
                AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT -> {
                    // Reflex: toggling the camera flash needs no LLM ladder.
                    val cam = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val flashIds = cam?.cameraIdList?.filter { id ->
                        cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }.orEmpty()
                    if (cam != null && flashIds.isNotEmpty()) {
                        cam.setTorchMode(flashIds.first(), true)
                        app.eventLogger.info("AniobViewModel", "Flashlight toggled on (reflex, no LLM)")
                        true
                    } else {
                        app.eventLogger.error("AniobViewModel", "Flashlight unavailable on this device")
                        false
                    }
                }
                AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO -> {
                    val state = AniobDeviceTelemetry.getRealState(app)
                    app.eventLogger.info(
                        "AniobViewModel",
                        "Device info: battery ${state.batteryPercent}% charging=${state.isCharging} " +
                            "thermal=${state.isThermalThrottled} network=${state.isNetworkAvailable} ram=${state.totalRamGb}GB"
                    )
                    // Surface the answer right back in the running transcript for M0-ExternalAI style tasks.
                    val summary = "Battery ${state.batteryPercent}% (charging: ${if (state.isCharging) "yes" else "no"}). " +
                        "RAM ${state.totalRamGb}GB. Thermal throttled: ${state.isThermalThrottled}. " +
                        "Network available: ${state.isNetworkAvailable}."
                    val deviceMsg = ChatMessage(
                        id = "msg_device_${System.currentTimeMillis()}",
                        role = "assistant",
                        content = "📱 $summary",
                        stepIndex = 0
                    )
                    _uiState.update { it.copy(chatMessages = it.chatMessages + deviceMsg) }
                    true
                }
                else -> {
                    val intent = Intent(shortcut.action).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        shortcut.targetPackage?.let { setPackage(it) }
                        shortcut.uriString?.let { data = Uri.parse(it) }
                        shortcut.category?.let { addCategory(it) }
                    }
                    app.startActivity(intent)
                    true
                }
            }
        } catch (e: Exception) {
            app.eventLogger.error("AniobViewModel", "Failed to launch system intent: ${e.message}")
            false
        }
    }

    fun stopCurrentTask() {
        val stoppingGen = ++activeGeneration
        val currentTask = _uiState.value.activeTask
        app.taskOwner.stop(currentTask?.id)
        activeTaskJob?.cancel()
        activeTaskJob = null

        app.approvalController.clearForTask(currentTask?.id ?: "")
        pendingConfirmation?.complete(ConfirmDecision.DENY)
        pendingConfirmation = null

        taskBlockedFingerprints.forEach { com.aniob.core.safety.AniobSafetyInterceptor.unblockFingerprint(it) }
        taskBlockedFingerprints.clear()

        AniobAccessibilityService.isTaskActive = false
        AniobForegroundService.clearConfirmation(app)
        hippocampusTracker.onFailure()

        val stoppedSummary = buildSummary(
            prompt = currentTask?.rawPrompt ?: "Task",
            outcome = Outcome.STOPPED,
            steps = _uiState.value.currentStep,
            durationMs = 0L,
            provider = _uiState.value.lastProviderUsed.ifBlank { "NONE" },
            evidence = emptyList(),
            reason = "Stopped. Earlier changes were not undone."
        )
        postSummaryCard(stoppedSummary)
        clearRunningMarker()

        AniobBackgroundController.onTaskFinished(app, "Stopped by user", isSuccess = false)
        _uiState.update { it.copy(isRunning = false, statusMessage = "Stopped. Earlier changes were not undone.") }
    }

    companion object {
        /** Upper bound for a single gesture callback before we give up on it. */
        const val SWIPE_CALLBACK_TIMEOUT_SECONDS = 5L

        /** SavedStateHandle key for the nav back-stack (UX-0 §2). */
        const val KEY_NAV_BACKSTACK = "nav_back_stack"

        /** SavedStateHandle key for the in-flight task marker (UX-6 process-death honesty). */
        const val KEY_RUNNING_MARKER = "running_task_marker"

        /** How often a paused task re-checks the resume flag (UX-3 stop/pause responsiveness). */
        const val PAUSE_POLL_MS = 100L

        /** Trust Center keeps the last 20 decisions (UX-4). */
        const val CONFIRMATION_HISTORY_LIMIT = 20

        /** Render window for the chat timeline; full history stays in the data list (U18). */
        const val CHAT_RENDER_WINDOW = 200

        /** Prompt words too generic to identify a target node (they describe intent, not label). */
        val SCROLL_STOPWORDS = setOf(
            "the", "and", "for", "with", "tap", "click", "press", "open", "scroll",
            "button", "this", "that", "then", "please", "from", "into", "your", "you"
        )
    }
}
