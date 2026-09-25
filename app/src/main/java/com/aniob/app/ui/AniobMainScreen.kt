package com.aniob.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.aniob.app.system.SystemState
import com.aniob.app.ui.screens.*
import com.aniob.app.ui.theme.AniobMotion
import com.aniob.core.narration.FailureReasonCopy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Root screen. Navigation is owned by the ViewModel's back-stack (UX-0 §2), so rotation keeps the
 * screen and Back never exits from a sub-screen. CHAT uses double-back-to-exit with a toast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobMainScreen(viewModel: AniobViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack by viewModel.navBackStack.collectAsStateWithLifecycle()
    val route = backStack.lastOrNull() ?: AniobRoute.CHAT
    val app = LocalContext.current.applicationContext as com.aniob.app.AniobApplication
    val systemState by app.systemState.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showWhyModelDialog by remember { mutableStateOf(false) }
    var lastBackPress by remember { mutableStateOf(0L) }
    var reducedMotion by remember { mutableStateOf(false) }

    // UX-7: honour the platform reduced-motion accessibility setting (static variants).
    LaunchedEffect(Unit) {
        reducedMotion = AniobMotion.isReducedMotionEnabled(context)
    }

    // UX-0: BackHandler only pops the stack; double-back exits only when already at root CHAT.
    BackHandler {
        if (viewModel.navigateBack()) {
            Unit
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPress < DOUBLE_BACK_MS) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackPress = now
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(animationSpec = AniobMotion.instant()) togetherWith fadeOut(animationSpec = AniobMotion.instant())
            } else {
                (fadeIn(AniobMotion.navSpec()) + slideInHorizontally(AniobMotion.navSpec()) { it / 8 }) togetherWith
                    (fadeOut(AniobMotion.navSpec()) + slideOutHorizontally(AniobMotion.navSpec()) { -it / 8 })
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "nav"
    ) { current ->
        when (current) {
            AniobRoute.CHAT -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "Aniob",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        uiState.isPaused -> "Paused"
                                        uiState.isRunning -> "Working · step ${uiState.currentStep}"
                                        else -> "Ready"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isRunning) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            val installedModel = viewModel.getInstalledModelName() ?: "No model"
                            val modeLabel = when (uiState.autoRouterMode.lowercase()) {
                                "local-first" -> "Local-first"
                                "local-only" -> "On-device"
                                "cloud-only" -> "Cloud"
                                "balanced" -> "Balanced"
                                else -> "Auto"
                            }
                            AssistChip(
                                onClick = { showWhyModelDialog = true },
                                label = { Text("$modeLabel · $installedModel") },
                                leadingIcon = {
                                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("model_selector_chip")
                            )

                            IconButton(
                                onClick = { viewModel.clearChat() },
                                modifier = Modifier.testTag("clear_chat_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear chat")
                            }

                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier.testTag("main_overflow_menu_btn")
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    MenuEntry("History", Icons.Default.History, "menu_history") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.HISTORY)
                                    }
                                    MenuEntry("Stats & progress", Icons.Default.BarChart, "menu_stats") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.STATS)
                                    }
                                    MenuEntry("Skills", Icons.Default.AutoAwesome, "menu_skills") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.SKILLS)
                                    }
                                    MenuEntry("Trust Center", Icons.Default.Shield, "menu_trust") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.TRUST)
                                    }
                                    MenuEntry("On-device models", Icons.Default.Memory, "menu_models") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.MODELS)
                                    }
                                    MenuEntry("Settings", Icons.Default.Settings, "menu_settings") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.SETTINGS)
                                    }
                                    MenuEntry("Setup & Permissions", Icons.Default.VerifiedUser, "menu_onboarding") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.ONBOARDING)
                                    }
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    AniobChatScreen(
                        uiState = uiState,
                        systemState = systemState,
                        onSubmitTask = { viewModel.submitTask(it) },
                        onStopTask = { viewModel.stopCurrentTask() },
                        onPauseToggle = { viewModel.togglePause() },
                        onShowTrackerSheet = { viewModel.setShowTrackerSheet(it) },
                        onFilterChanged = { viewModel.setTrackerFilter(it) },
                        onManageModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                        onOpenSetup = { viewModel.navigateTo(AniobRoute.ONBOARDING) },
                        onOpenSkills = { viewModel.navigateTo(AniobRoute.SKILLS) },
                        onDismissInterrupted = { viewModel.dismissInterruptedSummary() },
                        onDismissRecovery = { viewModel.dismissRecoveryBanner() },
                        onRunAgain = { prompt -> viewModel.submitTask(prompt) },
                        onViewSteps = { viewModel.setShowTrackerSheet(true) },
                        onMomentDismiss = { viewModel.clearMomentChips() },
                        onQueueFollowUp = { viewModel.queueFollowUp(it) },
                        onCancelQueuedTask = { viewModel.cancelQueuedFollowUp() },
                        onStartQueuedTask = { viewModel.startQueuedTask() },
                        renderWindow = { viewModel.renderWindow() }
                    )
                }
            }

            AniobRoute.HISTORY -> AniobHistoryScreen(
                uiState = uiState,
                onBack = { viewModel.navigateBack() },
                onNavigateToStats = { viewModel.navigateTo(AniobRoute.STATS) },
                onRunAgain = { prompt -> viewModel.resetToChat(); viewModel.submitTask(prompt) },
                onMakeSkill = { prompt ->
                    viewModel.startTeachFlow(prompt)
                    viewModel.navigateTo(AniobRoute.SKILLS)
                }
            )

            AniobRoute.STATS -> AniobStatsScreen(
                uiState = uiState,
                onBack = { viewModel.navigateBack() }
            )

            AniobRoute.MODELS -> AniobModelDownloadScreen(
                onBack = { viewModel.navigateBack() }
            )

            AniobRoute.SETTINGS -> AniobSettingsScreen(
                uiState = uiState,
                onSaveSettings = { key, model -> viewModel.updateSettings(key, model) },
                onAutoRouterModeChanged = { mode -> viewModel.setAutoRouterMode(mode) },
                onFastPathChanged = { viewModel.setFastPathEnabled(it) },
                onSafetyGateChanged = { viewModel.setSafetyGateEnabled(it) },
                onBack = { viewModel.navigateBack() },
                onNavigateToModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                onNavigateToStats = { viewModel.navigateTo(AniobRoute.STATS) },
                onNavigateToOnboarding = { viewModel.navigateTo(AniobRoute.ONBOARDING) },
                onNavigateToTrust = { viewModel.navigateTo(AniobRoute.TRUST) }
            )

            AniobRoute.ONBOARDING -> AniobSetupConciergeScreen(
                systemState = systemState,
                onBack = { viewModel.navigateBack() },
                onNavigateToModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                onRefresh = { app.systemState.refresh() },
                onRunDemoTask = {
                    viewModel.resetToChat()
                    viewModel.submitTask("Open Settings")
                }
            )

            AniobRoute.SKILLS -> AniobSkillsScreen(
                uiState = uiState,
                skills = viewModel.getLoadedSkills(),
                onBack = { viewModel.navigateBack() },
                onTeach = { viewModel.startTeachFlow(null) },
                onReplay = { prompt -> viewModel.resetToChat(); viewModel.submitTask(prompt) },
                onToggleSkill = { name, enabled -> viewModel.setSkillEnabled(name, enabled) },
                onDeleteSkill = { name -> viewModel.deleteSkill(name) },
                vaultEntries = { viewModel.vaultEntries() },
                onForgetVault = { key -> viewModel.forgetVaultEntry(key) }
            )

            AniobRoute.TRUST -> AniobTrustCenterScreen(
                onBack = { viewModel.navigateBack() },
                safetyLevel = uiState.safetyLevel,
                onSafetyLevelChanged = { viewModel.setSafetyLevel(it) },
                fastPathEnabled = viewModel.isFastPathEnabled(),
                onFastPathChanged = { viewModel.setFastPathEnabled(it) },
                gateEnabled = viewModel.isSafetyGateEnabled(),
                onGateChanged = { viewModel.setSafetyGateEnabled(it) },
                blockedEntries = uiState.blockedActions,
                onClearBlocked = { fp -> viewModel.clearBlockedFingerprint(fp) },
                confirmationHistory = uiState.confirmationHistory,
                vaultEntries = { viewModel.vaultEntries() },
                onForgetVault = { key -> viewModel.forgetVaultEntry(key) }
            )
        }
    }

    // UX-2/UX-5: Remembered Moments + skill replay chips surface in chat, not logs.
    if (uiState.momentChips.isNotEmpty()) {
        MomentChipRow(chips = uiState.momentChips, onDismiss = { viewModel.clearMomentChips() })
    }

    // Grill-Me 2.0 (UX-2): remembers which questions were answered from the vault.
    if (uiState.showGrillMeSheet && uiState.grillMeResult != null) {
        AniobGrillMeSheet(
            grillResult = uiState.grillMeResult!!,
            rememberedKeys = uiState.grillRememberedKeys,
            onConfirm = { answers, rememberKeys -> viewModel.onGrillAnswersSubmitted(answers, rememberKeys) },
            onDismiss = { viewModel.cancelGrillMe() }
        )
    }

    val confirmRequest = uiState.confirmRequest
    if (confirmRequest != null) {
        ConfirmSheet(
            request = confirmRequest,
            onDecision = { decision -> viewModel.resolveConfirmation(decision) }
        )
    }

    if (showWhyModelDialog) {
        val installedModel = viewModel.getInstalledModelName() ?: "No local model installed"
        val reason = viewModel.getModelChoiceReason()
        AlertDialog(
            onDismissRequest = { showWhyModelDialog = false },
            title = { Text("How Aniob is thinking") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode: ${FailureReasonCopy.providerLabel(uiState.autoRouterMode)}", fontWeight = FontWeight.Bold)
                    Text("Brain: $installedModel")
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showWhyModelDialog = false
                    viewModel.navigateTo(AniobRoute.MODELS)
                }) { Text("Manage brains") }
            },
            dismissButton = {
                TextButton(onClick = { showWhyModelDialog = false }) { Text("Dismiss") }
            }
        )
    }
}

@Composable
private fun MenuEntry(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag)
    )
}

/** Compact chip row for Remembered Moments / skill replay (UX-5 §2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentChipRow(chips: List<com.aniob.app.ui.model.MomentChip>, onDismiss: () -> Unit) {
    // Rendered as a transient overlay row above the chat input.
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .testTag("moment_chip_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { chip ->
                AssistChip(
                    onClick = onDismiss,
                    label = { Text(chip.label) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("moment_chip_${chip.id}")
                )
            }
        }
    }
}

private const val DOUBLE_BACK_MS = 2000L

