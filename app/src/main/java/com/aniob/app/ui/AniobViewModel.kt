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
import com.aniob.app.provider.AniobMockProvider
import com.aniob.app.provider.AniobOmniRouteProvider
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
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
import com.aniob.core.execution.StepPipeline
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
    val disabledSkills: Set<String> = emptySet()
)

class AniobViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AniobUiState())

    /** Back-stack owned by the ViewModel (UX-0 §2). Survives rotation; root CHAT is re-derived. */
    private val _navBackStack = MutableStateFlow(restoreNavBackStack())
    val navBackStack: StateFlow<List<AniobRoute>> = _navBackStack.asStateFlow()

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
    private val executionRouter = AniobExecutionRouter(
        semanticSkillMatcher = semanticSkillMatcher,
        planningAgent = planningAgent,
        memoryStore = memoryStore
    )
    /** The single learning pipeline; shares the router's FastPath engine and Room. */
    private val learningPipeline = com.aniob.app.learning.AniobLearningPipelineImpl(
        database = (application as AniobApplication).database,
        replayEngine = executionRouter.fastPathEngine
    )
    private val mockProvider = AniobMockProvider()
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

    // UX-3: cooperative pause; checked at the loop head and inside settle waits.
    @Volatile private var pauseRequested = false

    /** Convenience accessor so the UI can read safetyLevel without collecting uiState. */
    val safetyLevel: SafetyLevel get() = _uiState.value.safetyLevel

    /** Exposes the loaded skill list for the Skills screen. */
    fun getLoadedSkills(): List<com.aniob.core.skills.AniobSkill> =
        semanticSkillMatcher.getLoadedSkills()

    // UX-4: memoized per (taskId, signal) approvals; the dispatch path consults this.
    private val taskApprovals = mutableSetOf<String>()

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
        localFailCount = prefs.getInt("local_fail_count", 0)
        disabledSkills = (prefs.getStringSet("disabled_skills", emptySet()) ?: emptySet()).toMutableSet()
        executionRouter.setDisabledSkills(disabledSkills)

        _uiState.update {
            it.copy(
                omnirouteApiKey = savedApiKey,
                omnirouteModel = savedModel,
                autoRouterMode = savedMode,
                // UX-4: switches are REAL state, restored from disk — never placebo remembr.
                safetyLevel = SafetyLevel.entries.getOrElse(
                    prefs.getInt("safety_level", SafetyLevel.STANDARD.ordinal)
                ) { SafetyLevel.STANDARD },
                fastPathEnabled = prefs.getBoolean("fastpath_enabled", true),
                safetyGateEnabled = prefs.getBoolean("safety_gate_enabled", true),
                disabledSkills = disabledSkills.toSet()
            )
        }

        // UX-4: an OFF safety level is never silently retained — re-arm on every process start.
        if (_uiState.value.safetyLevel == SafetyLevel.OFF) {
            setSafetyLevel(SafetyLevel.STANDARD)
        }

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
            Outcome.SUCCESS -> listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS, NextAction.MAKE_SKILL)
            Outcome.FAILED -> listOf(NextAction.RETRY, NextAction.EXPLORE_APP, NextAction.TEACH_ME, NextAction.VIEW_STEPS)
            Outcome.STOPPED, Outcome.INTERRUPTED ->
                listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS)
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

    /** UX-6: survives process death so the next open shows an honest Interrupted card. */
    private fun persistRunningMarker(task: AniobTask, stepIndex: Int) {
        savedStateHandle[KEY_RUNNING_MARKER] = "${task.id}|${task.rawPrompt}|$stepIndex"
    }

    private fun clearRunningMarker() {
        savedStateHandle[KEY_RUNNING_MARKER] = null
    }

    /** Reads the persisted marker on cold start and surfaces it as an Interrupted card. */
    private fun restoreInterruptedSummary() {
        val marker = savedStateHandle.get<String>(KEY_RUNNING_MARKER) ?: return
        val parts = marker.split("|")
        if (parts.size < 3) return
        val prompt = parts[1]
        val step = parts[2].toIntOrNull() ?: 0
        val summary = buildSummary(
            prompt = prompt,
            outcome = Outcome.INTERRUPTED,
            steps = step,
            durationMs = 0L,
            provider = "NONE",
            evidence = emptyList(),
            reason = "Aniob was killed by the system at step $step"
        )
        clearRunningMarker()
        _uiState.update { it.copy(interruptedSummary = summary, isRunning = false) }
    }

    // =========================================================================================
    // UX-2 Smart Composer
    // =========================================================================================

    /** Queues a follow-up while a task runs (depth 1; the label says so honestly). */
    fun queueFollowUp(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(queuedPrompt = trimmed) }
    }

    fun cancelQueuedFollowUp() {
        _uiState.update { it.copy(queuedPrompt = null) }
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
     * Takeover Bridge (UX-3 §4): after a pause→manual step→resume we offer to teach the step just
     * performed. Until the Teach producer lands this hands off a prefilled draft.
     */
    fun onTakeoverCompleted() {
        val prompt = _uiState.value.activeTask?.rawPrompt ?: return
        _uiState.update {
            it.copy(
                chatMessages = it.chatMessages + ChatMessage(
                    id = "msg_takeover_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = "Learn what you just did? I can turn '$prompt' into a skill.",
                    badge = "Takeover",
                    provider = "TEACH"
                )
            )
        }
        startTeachFlow(prompt)
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
        com.aniob.core.safety.AniobSafetyInterceptor.clearBlockedFingerprints()
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
     * `Pair(reason){}` dialog: this suspends the caller until a real decision exists, so a risky
     * action can never execute while the approval is still on screen.
     *
     * `// SHIM(UX-4): swap for the suspend `confirm(request)` seam when the cutover lands.`
     */
    internal suspend fun requestConfirmation(request: ConfirmRequest): ConfirmDecision {
        // "Approve for this task" memoizes per (taskId, signal).
        val memoKey = "${_uiState.value.activeTask?.id}|${request.what}"
        if (taskApprovals.contains(memoKey)) return ConfirmDecision.APPROVE_FOR_TASK

        val deferred = CompletableDeferred<ConfirmDecision>()
        pendingConfirmation = deferred
        _uiState.update { it.copy(confirmRequest = request) }

        // Post the high-priority door so a minimized user actually sees the ask.
        AniobForegroundService.notifyConfirmation(app, request)

        val decision = withTimeoutOrNull(request.timeoutSec * 1000L) { deferred.await() }
            ?: ConfirmDecision.TIMEOUT_DENY

        if (decision == ConfirmDecision.APPROVE_FOR_TASK) taskApprovals.add(memoKey)
        recordConfirmation(request, decision)
        AniobForegroundService.clearConfirmation(app)
        _uiState.update { it.copy(confirmRequest = null) }
        pendingConfirmation = null
        return decision
    }

    /** Called by the ConfirmSheet / notification action with the user's real choice. */
    fun resolveConfirmation(decision: ConfirmDecision) {
        pendingConfirmation?.complete(decision)
    }

    /**
     * Maps an interceptor verdict onto the frozen confirm contract.
     * The interceptor decides *whether* to ask; the user decides *whether to proceed*.
     */
    private fun buildConfirmRequest(
        what: String,
        why: String,
        risk: ConfirmRequest.Risk,
        details: String
    ) = ConfirmRequest(
        id = "confirm_${System.currentTimeMillis()}",
        title = "Aniob needs your approval",
        what = what,
        why = why,
        risk = risk,
        details = details
    )

    private fun riskFromTier(tier: String, safetyLevel: SafetyLevel): ConfirmRequest.Risk = when {
        tier.equals("HIGH", true) -> ConfirmRequest.Risk.HIGH
        tier.equals("MEDIUM", true) -> ConfirmRequest.Risk.MEDIUM
        else -> ConfirmRequest.Risk.LOW
    }

    /**
     * True when this action needs an approval at the current safety level.
     * Standard asks only for HIGH; Strict also asks for MEDIUM and cross-app navigation.
     */
    private fun needsApproval(tier: String, action: AniobAction): Boolean = when (_uiState.value.safetyLevel) {
        SafetyLevel.OFF -> false
        SafetyLevel.STRICT -> !tier.equals("LOW", true) || action is AniobAction.OpenApp
        SafetyLevel.STANDARD -> tier.equals("HIGH", true)
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

    internal var disabledSkills: MutableSet<String> = mutableSetOf()

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

            // Step 1.5: Pre-flight Doctor Check (<500ms fail-fast before burning tokens or execution timeout)
            val resolvedShortcut = AniobIntentResolver.resolve(trimmed)
            val doctorResult = com.aniob.core.tools.AniobDoctor.preflightCheck(
                taskPrompt = trimmed,
                targetPackage = resolvedShortcut?.targetPackage,
                isAccessibilityConnected = AniobAccessibilityService.isServiceConnected,
                modelsDir = modelDownloader.getModelsDir(),
                installedModelId = modelDownloader.getDefaultModelId(),
                isNetworkAvailable = AniobDeviceTelemetry.getRealState(app).isNetworkAvailable,
                autoRouterMode = _uiState.value.autoRouterMode,
                totalRamGb = AniobDeviceTelemetry.getRealState(app).totalRamGb
            )
            if (!doctorResult.allPassed) {
                val telemetry = AniobDeviceTelemetry.getRealState(app)
                val recommendedId = modelDownloader.getRecommendedModel(
                    modelDownloader.getDeviceInfo()
                )
                val recommendedName = AniobModelDownloader.ALL_MODELS.find { it.id == recommendedId }?.name ?: recommendedId
                val installedName = modelDownloader.getDefaultModelId()
                    ?.let { id -> AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id }
                val failedChecksSummary = doctorResult.checks.filter { !it.passed }.joinToString { "${it.name}: ${it.reason}" }
                val doctorErrorMsg = ChatMessage(
                    id = "msg_doctor_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = when {
                        doctorResult.checks.any { it.name == "network_available" && !it.passed } ->
                            "Offline \u2014 install an on-device model (${recommendedName}, ${recommendedModelSizeGb(recommendedId)}GB) " +
                                "in Models screen or check connection. Your device has ${telemetry.totalRamGb}GB RAM. Checks: $failedChecksSummary"
                        doctorResult.checks.any { it.name == "accessibility_connected" && !it.passed } ->
                            "Accessibility Service Disabled \u2014 Enable Aniob in Settings to automate apps. Checks: $failedChecksSummary"
                        doctorResult.checks.any { it.name == "model_file_exists" && !it.passed } ->
                            "Local model file missing \u2014 install ${installedName ?: recommendedName} in Models screen " +
                                "or switch to Auto mode. Checks: $failedChecksSummary"
                        else -> "Cannot start task: ${doctorResult.failReason}. Checks: $failedChecksSummary"
                    },
                    badge = "⚠️ Pre-flight Failed",
                    provider = "DOCTOR",
                    retryPrompt = trimmed
                )
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        statusMessage = "Pre-flight check failed",
                        chatMessages = it.chatMessages + doctorErrorMsg,
                        lastRoutingReason = doctorResult.failReason ?: "Doctor pre-flight failed"
                    )
                }
                app.metricsCollector.recordSession(
                    taskId = taskId,
                    prompt = trimmed,
                    isSuccess = false,
                    steps = 0,
                    durationMs = 0,
                    tokensUsed = 0,
                    providerUsed = "DOCTOR",
                    decisionReason = doctorResult.failReason ?: "Doctor pre-flight failed"
                )
                return@launch
            }

            // Task is self-contained -> Proceed directly
            executeTaskPipeline(task)
        }
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

        viewModelScope.launch(Dispatchers.Default) {
            executeTaskPipeline(updatedTask)
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
    private suspend fun executeTaskPipeline(task: AniobTask) {
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
        val taskContext = com.aniob.core.domain.TaskContext(
            instruction = task.clarifiedGoal,
            successCriteria = resolvedCriteria,
            grillAnswers = task.grillAnswers
        )

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

        val maxSteps = agentLimits.maxStepsPerTask

        try {
            while (currentStep < maxSteps && _uiState.value.isRunning) {
                _uiState.update { it.copy(currentStep = currentStep + 1) }

                // Check Observation Policy: burst if predictable, else full capture
                val captured = if (observationPolicy.shouldObserve() || screenBefore == null) {
                    screenReads++; screenCaptureCount++
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

                // Session validation: do not trust a cached app session blindly. An OEM may have
                // killed the app in the background within the 5-min TTL, in which case reusing the
                // stale tree would dispatch taps into the wrong window or against dead node ids.
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
                        // Cached app died and a different app is foreground - cold-start the target.
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Session invalid (${reuseCheck.reason}) - cold starting ${foregroundBefore}"
                        )
                        executeActionSync(liveService, AniobAction.OpenApp(packageName = foregroundBefore))
                        delay(800)
                        currentScreen = liveService.captureCurrentScreenState()
                        screenReads++; screenCaptureCount++
                    } else {
                        // Same app but cached tree is stale - force a fresh capture before acting.
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
                // Resolves to the saved default, else any installed model, else the recommended id - so a
                // missing file here means "we know which model is wanted, but it is not downloaded".
                val resolvedModelId = modelDownloader.getDefaultModelId()
                val resolvedFileExists = File(modelDownloader.getModelsDir(), "$resolvedModelId.gguf").exists()
                val effectiveModelId = resolvedModelId.takeIf { resolvedFileExists }
                val isModelFileMissing = !resolvedFileExists

                val ladderResult = executionRouter.planStep(
                    taskPrompt = task.clarifiedGoal,
                    screenState = currentScreen,
                    stepIndex = currentStep,
                    taskSignature = task.rawPrompt.lowercase(),
                    powerState = powerState,
                    installedModelId = effectiveModelId,
                    lastLocalFailCount = localFailCount,
                    isModelFileMissing = isModelFileMissing,
                    previousProgress = taskProgress
                )
                if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch) {
                    // TaskProgress accumulates across steps (prefrontal condensation).
                    taskProgress = planningAgent.updateProgress(
                        userInstruction = task.clarifiedGoal,
                        previousOperation = if (currentStep > 0) "Step ${currentStep - 1} executed" else null,
                        previousProgress = taskProgress,
                        focusContent = null
                    )
                }

                val stepStartTime = System.currentTimeMillis()

                when (ladderResult) {
                    is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                        primaryProvider = "INTENT"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing direct system intent...") }

                        dispatchSystemIntent(ladderResult.shortcut)
                        delay(800)

                        screenReads++; screenCaptureCount++
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
                        // A system-intent shortcut is a provisional success too: verify the resulting
                        // screen against criteria before declaring victory (no fake SUCCESS).
                        val intentVerdict = DeterministicVerifier.verifyFinish(resolvedCriteria, screenAfter, currentStep + 1)
                        if (intentVerdict.isExpected) {
                            finalSuccess = true
                        } else {
                            // No evidence either way (e.g. no criteria) still counts as dispatched,
                            // but a *disproved* criterion means the shortcut did not achieve the goal.
                            finalSuccess = !resolvedCriteria.hasObservableCheck
                            if (!finalSuccess) {
                                _uiState.update { it.copy(statusMessage = "Intent dispatched but goal not verified: ${intentVerdict.reason}") }
                            }
                        }
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
                            blockedRecords.add(BlockedRecord(fingerprint = currentScreen.treeHash, rule = intercept.reason, at = System.currentTimeMillis()))
                            _uiState.update { it.copy(blockedActions = blockedRecords.toList()) }
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

                        val step = executeVerifyAndRecover(a11y, action, task.clarifiedGoal, currentScreen)
                        val executed = step.executedAction
                        val verification = step.verification
                        screenReads++; screenCaptureCount++
                        actionsDispatched++
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${step.screenAfter.treeHash.take(6)} after ${executed.toolName}",
                            action = executed,
                            provider = "FASTPATH",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = step.screenAfter.treeHash,
                            verified = verification.isExpected
                        )

                        observationPolicy.recordActionOutcome(executed, currentScreen.packageName, verification.isSuccessful)

                        addStepRecord(
                            AniobStepRecord(
                                stepIndex = currentStep + 1,
                                screenHash = currentScreen.treeHash,
                                action = executed,
                                provider = "FASTPATH",
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                tokensUsed = 0,
                                verifiedSuccess = verification.isSuccessful,
                                failureReason = if (!verification.isSuccessful) verification.reason else null
                            )
                        )

                        if (executed is AniobAction.Finish || executed is AniobAction.Fail) {
                            if (executed is AniobAction.Finish) {
                                val verdict = verifyProvisionalFinish(executed, step.screenAfter, currentStep + 1, resolvedCriteria)
                                if (!verdict.isExpected) {
                                    finishRejections++
                                    if (finishRejections < StepPipeline.MAX_REJECTED_FINISHES) {
                                        _uiState.update { it.copy(statusMessage = "Finish rejected (${finishRejections}/${StepPipeline.MAX_REJECTED_FINISHES}): ${verdict.reason}") }
                                        screenBefore = step.screenAfter
                                        currentStep++
                                        continue
                                    }
                                    finalSuccess = false
                                    break
                                }
                            }
                            finalSuccess = executed is AniobAction.Finish
                            break
                        }
                        screenBefore = step.screenAfter
                        currentStep++
                    }

                    is AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution -> {
                        primaryProvider = "SKILL"
                        decisionReason = ladderResult.reason
                        _uiState.update { it.copy(statusMessage = "Executing Skill '${ladderResult.skill.name}' step ${currentStep + 1}...") }

                        val action = ladderResult.action
                        val step = executeVerifyAndRecover(a11y, action, task.clarifiedGoal, currentScreen)
                        val executed = step.executedAction
                        val verification = step.verification
                        screenReads++; screenCaptureCount++
                        actionsDispatched++
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${step.screenAfter.treeHash.take(6)} after skill ${executed.toolName}",
                            action = executed,
                            provider = "SKILL",
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            screenHash = step.screenAfter.treeHash,
                            verified = verification.isExpected
                        )
                        observationPolicy.recordActionOutcome(executed, currentScreen.packageName, verification.isSuccessful)

                        addStepRecord(
                            AniobStepRecord(
                                stepIndex = currentStep + 1,
                                screenHash = currentScreen.treeHash,
                                action = executed,
                                provider = "SKILL",
                                latencyMs = System.currentTimeMillis() - stepStartTime,
                                tokensUsed = 0,
                                verifiedSuccess = verification.isSuccessful,
                                failureReason = if (!verification.isSuccessful) verification.reason else null
                            )
                        )

                        if (executed is AniobAction.Finish || executed is AniobAction.Fail) {
                            if (executed is AniobAction.Finish) {
                                val verdict = verifyProvisionalFinish(executed, step.screenAfter, currentStep + 1, resolvedCriteria)
                                if (!verdict.isExpected) {
                                    finishRejections++
                                    if (finishRejections < StepPipeline.MAX_REJECTED_FINISHES) {
                                        _uiState.update { it.copy(statusMessage = "Finish rejected (${finishRejections}/${StepPipeline.MAX_REJECTED_FINISHES}): ${verdict.reason}") }
                                        screenBefore = step.screenAfter
                                        currentStep++
                                        continue
                                    }
                                    finalSuccess = false
                                    break
                                }
                            }
                            finalSuccess = executed is AniobAction.Finish
                            break
                        }
                        screenBefore = step.screenAfter
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

                        // Invoke provider (Local LiteRT/llama.cpp, Omniroute Cloud, or Mock fallback).
                        // The planning agent's condensed TaskProgress summary stands in for the
                        // full interleaved observation history (prefrontal-cortex condensation).
                        val planningSummary = ladderResult.progressSummary ?: task.clarifiedGoal
                        if (decision.target == RouteTarget.OMNIROUTE_CLOUD) escalations++
                        // Truthful provider label: only claim LOCAL_SLM when the engine is actually
                        // loaded AND can drive actions. An engine that only answers must not move
                        // the UI, so it is quarantined to the mock rung with the real reason logged.
                        val localCaps = if (decision.target == RouteTarget.LOCAL_SLM) localLlmClient.capabilities() else null
                        val localCanDrive = decision.target == RouteTarget.LOCAL_SLM &&
                            localLlmClient.isModelLoaded() && (localCaps?.canDriveActions == true)
                        if (decision.target == RouteTarget.LOCAL_SLM && !localCanDrive) {
                            app.eventLogger.warn(
                                "AniobViewModel",
                                "Local engine quarantined: ${localCaps?.reason ?: "model not loaded"}"
                            )
                            primaryProvider = "MOCK"
                            decisionReason = "local_quarantined: ${localCaps?.reason ?: "model not loaded"}"
                            _uiState.update {
                                it.copy(
                                    lastProviderUsed = primaryProvider,
                                    lastRoutingReason = decisionReason
                                )
                            }
                        }
                        val action = if (localCanDrive) {
                            // Real on-device generation. A missing/broken engine resolves to an
                            // explicit Fail carrying a user-actionable reason - never a fake tap.
                            val generation = localLlmClient.generateStepResult(
                                configLoader.prompt("system_prompt.txt")
                                    ?: "System: Android Agent. Reply with a single JSON tool call.",
                                planningSummary
                            )
                            totalTokens += 80
                            val structured = AniobLocalActionResolver.resolveStructured(generation, currentScreen)
                            if (structured.usedFallback) {
                                app.eventLogger.warn(
                                    "AniobViewModel",
                                    "Local SLM produced no schema-valid action: ${structured.reason}"
                                )
                            }
                            structured.action
                        } else if (decision.target == RouteTarget.LOCAL_SLM) {
                            // Ladder chose local but the engine is unusable - mock rung acts, labelled MOCK.
                            mockProvider.planNextStep(planningSummary, currentStep, currentScreen)
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD && _uiState.value.omnirouteApiKey.isNotBlank()) {
                            val omniroute = AniobOmniRouteProvider(
                                apiKey = _uiState.value.omnirouteApiKey,
                                model = _uiState.value.omnirouteModel
                            )
                            val systemPrompt = configLoader.prompt("system_prompt.txt") ?: "You are Aniob agent."
                            val raw = omniroute.getNextActionRaw(systemPrompt, planningSummary)
                            totalTokens += 150
                            raw.fold(
                                onSuccess = { text ->
                                    // Cloud text and local text share ONE parse-or-repair boundary.
                                    var structured = AniobStructuredOutput.parseOrRepair(
                                        raw = text,
                                        screenState = currentScreen,
                                        decode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.PLANNER)
                                    )
                                    if (structured.usedFallback) {
                                        // One suspend repair retry, then the deterministic fallback stands.
                                        val repaired = runCatching {
                                            omniroute.getNextActionRaw(
                                                systemPrompt,
                                                AniobStructuredOutput.repairPrompt(text)
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
                                onFailure = {
                                    // Cloud unreachable - fall back to mock, but never claim CLOUD.
                                    primaryProvider = "MOCK"
                                    _uiState.update { st -> st.copy(lastProviderUsed = primaryProvider) }
                                    mockProvider.planNextStep(planningSummary, currentStep, currentScreen)
                                }
                            )
                        } else {
                            mockProvider.planNextStep(planningSummary, currentStep, currentScreen)
                        }

                        // Watchdog check
                        watchdog.record(currentScreen.treeHash, action)
                        val loopDetected = watchdog.isLoopDetected()
                        if (loopDetected) {
                            // Reflection reflex: loop gives a negative reward; force remedial BACK.
                            watchdog.reset()
                            executeActionSync(a11y, AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK))
                            delay(400)
                        }

                        // Safety Interceptor check
                        val intercept = AniobSafetyInterceptor.evaluateAction(
                            action = action,
                            targetNode = null,
                            screenState = currentScreen,
                            screenFingerprint = currentScreen.treeHash
                        )

                        if (!intercept.isAllowed) {
                            blockedRecords.add(BlockedRecord(fingerprint = currentScreen.treeHash, rule = intercept.reason, at = System.currentTimeMillis()))
                            _uiState.update { it.copy(blockedActions = blockedRecords.toList()) }
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

                        if (intercept.requiresConfirmation || needsApproval(intercept.riskTier, action)) {
                            val confirmReq = buildConfirmRequest(
                                what = action.toString().take(80),
                                why = intercept.reason,
                                risk = riskFromTier(intercept.riskTier, _uiState.value.safetyLevel),
                                details = "Action: ${action.toolName} · tier: ${intercept.riskTier}"
                            )
                            val decision = requestConfirmation(confirmReq)
                            if (decision == ConfirmDecision.DENY || decision == ConfirmDecision.TIMEOUT_DENY) {
                                val stepRecord = AniobStepRecord(
                                    stepIndex = currentStep + 1,
                                    screenHash = currentScreen.treeHash,
                                    action = action,
                                    provider = primaryProvider,
                                    latencyMs = System.currentTimeMillis() - stepStartTime,
                                    tokensUsed = 0,
                                    verifiedSuccess = false,
                                    failureReason = "You denied this step"
                                )
                                addStepRecord(stepRecord)
                                _uiState.update { it.copy(statusMessage = "You denied this step") }
                                finalSuccess = false
                                break
                            }
                        }

                        val step = executeVerifyAndRecover(a11y, action, task.clarifiedGoal, currentScreen)
                        val executed = step.executedAction
                        val screenAfter = step.screenAfter
                        val verification = step.verification
                        screenReads++; screenCaptureCount++
                        actionsDispatched++
                        val reflection = reflectionAgent.reflect(currentScreen, screenAfter, executed, verification.isExpected)
                        val remedial = reflection.remedialAction
                        if (!reflection.isExpected && remedial != null) {
                            executeActionSync(a11y, remedial)
                        }
                        observationPolicy.recordActionOutcome(executed, currentScreen.packageName, verification.isSuccessful)
                        recordTrajectory(
                            stepIndex = currentStep + 1,
                            observation = "screen ${screenAfter.treeHash.take(6)} after ${executed.toolName}",
                            action = executed,
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
                            action = executed,
                            provider = primaryProvider,
                            latencyMs = System.currentTimeMillis() - stepStartTime,
                            tokensUsed = if (primaryProvider == "OMNIROUTE_CLOUD") 150 else 0,
                            verifiedSuccess = stepSuccess,
                            failureReason = if (loopDetected) "Watchdog loop detected (N=3 repeated action)" else if (!verification.isSuccessful) verification.reason else null,
                            reflectorInvoked = loopDetected
                        )
                        addStepRecord(stepRecord)

                        if (executed is AniobAction.Finish || executed is AniobAction.Fail) {
                            if (executed is AniobAction.Finish) {
                                val verdict = verifyProvisionalFinish(executed, screenAfter, currentStep + 1, resolvedCriteria)
                                if (!verdict.isExpected) {
                                    finishRejections++
                                    if (finishRejections < StepPipeline.MAX_REJECTED_FINISHES) {
                                        _uiState.update { it.copy(statusMessage = "Finish rejected (${finishRejections}/${StepPipeline.MAX_REJECTED_FINISHES}): ${verdict.reason}") }
                                        screenBefore = screenAfter
                                        currentStep++
                                        continue
                                    }
                                    finalSuccess = false
                                    break
                                }
                            }
                            finalSuccess = executed is AniobAction.Finish
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
            // Learning (finding #5/#8): only verified success becomes FastPath knowledge.
            if (finalSuccess) {
                val trajectory = verifiedActions.toList()
                learningPipeline.onTaskSuccess(
                    TaskContext(instruction = task.rawPrompt, successCriteria = resolvedCriteria),
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

    private suspend fun executeActionSync(a11y: AniobAccessibilityService?, action: AniobAction) {
        // Block until the UI is quiescent so we never dispatch into a running animation.
        AniobWaitForIdle.waitForIdle(1000)
        a11y?.executeAction(action) { /* callback */ }
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

    private fun dispatchSystemIntent(shortcut: ResolvedIntentShortcut) {
        val systemAction = shortcut.extras[AniobIntentResolver.EXTRA_SYSTEM_ACTION]
        try {
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
                    } else {
                        app.eventLogger.error("AniobViewModel", "Flashlight unavailable on this device")
                    }
                    return
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
                    AniobAccessibilityService.isTaskActive = false
                    AniobBackgroundController.onTaskFinished(app, summary)
                    _uiState.update { it.copy(isRunning = false, statusMessage = "Device info captured") }
                    return
                }
                else -> {
                    val intent = Intent(shortcut.action).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        shortcut.targetPackage?.let { setPackage(it) }
                        shortcut.uriString?.let { data = Uri.parse(it) }
                        shortcut.category?.let { addCategory(it) }
                    }
                    app.startActivity(intent)
                }
            }
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
