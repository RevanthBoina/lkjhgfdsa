package com.aniob.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniob.app.ui.screens.*

enum class AniobScreen {
    CHAT,
    HISTORY,
    STATS,
    MODELS,
    SETTINGS,
    ONBOARDING
}

/**
 * Expert Main Screen (AIM Phase 1.1 - No Bottom Nav Forest).
 * Normal mobile app UX (WhatsApp / Telegram / Google Settings style).
 * - Home = Chat only.
 * - TopAppBar with Title, Model Chip, Running indicator, and Overflow Menu (⋮).
 * - Clean screen transitions between CHAT, HISTORY, STATS, MODELS, SETTINGS, ONBOARDING.
 * - Tracker bottom sheet (0 captures, 0 model calls).
 * - Grill-Me bottom sheet when task ambiguity detected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobMainScreen(viewModel: AniobViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(AniobScreen.CHAT) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    var showWhyModelDialog by remember { mutableStateOf(false) }

    when (currentScreen) {
        AniobScreen.CHAT -> {
            Scaffold(
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
                                    text = if (uiState.isRunning) "Running · Step ${uiState.currentStep}" else "Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            // Model selector chip button (Addition A: mode + modelName & reason explanation)
                            val installedModel = viewModel.getInstalledModelName() ?: "No Model"
                            val modeLabel = when (uiState.autoRouterMode.lowercase()) {
                                "local-first" -> "Local-First"
                                "local-only" -> "Local-Only"
                                "cloud-only" -> "Cloud"
                                "balanced" -> "Balanced"
                                else -> "Auto"
                            }
                            AssistChip(
                                onClick = { showWhyModelDialog = true },
                                label = {
                                    Text("$modeLabel · $installedModel")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("model_selector_chip")
                            )

                            // Clear chat action
                            IconButton(
                                onClick = { viewModel.clearChat() },
                                modifier = Modifier.testTag("clear_chat_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Chat")
                            }

                            // Overflow Menu (⋮)
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
                                    DropdownMenuItem(
                                        text = { Text("History") },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            currentScreen = AniobScreen.HISTORY
                                        },
                                        modifier = Modifier.testTag("menu_history")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Stats & Performance") },
                                        leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            currentScreen = AniobScreen.STATS
                                        },
                                        modifier = Modifier.testTag("menu_stats")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("On-Device AI Models") },
                                        leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            currentScreen = AniobScreen.MODELS
                                        },
                                        modifier = Modifier.testTag("menu_models")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            currentScreen = AniobScreen.SETTINGS
                                        },
                                        modifier = Modifier.testTag("menu_settings")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Setup & Permissions") },
                                        leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            currentScreen = AniobScreen.ONBOARDING
                                        },
                                        modifier = Modifier.testTag("menu_onboarding")
                                    )
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
                        onSubmitTask = { viewModel.submitTask(it) },
                        onStopTask = { viewModel.stopCurrentTask() },
                        onShowTrackerSheet = { viewModel.setShowTrackerSheet(it) },
                        onFilterChanged = { viewModel.setTrackerFilter(it) }
                    )
                }
            }
        }

        AniobScreen.HISTORY -> {
            AniobHistoryScreen(
                uiState = uiState,
                onBack = { currentScreen = AniobScreen.CHAT },
                onNavigateToStats = { currentScreen = AniobScreen.STATS }
            )
        }

        AniobScreen.STATS -> {
            AniobStatsScreen(
                uiState = uiState,
                onBack = { currentScreen = AniobScreen.CHAT }
            )
        }

        AniobScreen.MODELS -> {
            AniobModelDownloadScreen(
                onBack = { currentScreen = AniobScreen.CHAT }
            )
        }

        AniobScreen.SETTINGS -> {
            AniobSettingsScreen(
                uiState = uiState,
                onSaveSettings = { key, model -> viewModel.updateSettings(key, model) },
                onAutoRouterModeChanged = { mode -> viewModel.setAutoRouterMode(mode) },
                onBack = { currentScreen = AniobScreen.CHAT },
                onNavigateToModels = { currentScreen = AniobScreen.MODELS },
                onNavigateToStats = { currentScreen = AniobScreen.STATS },
                onNavigateToOnboarding = { currentScreen = AniobScreen.ONBOARDING }
            )
        }

        AniobScreen.ONBOARDING -> {
            AniobOnboardingScreen(
                onBack = { currentScreen = AniobScreen.CHAT },
                onNavigateToModels = { currentScreen = AniobScreen.MODELS },
                onNavigateToSettings = { currentScreen = AniobScreen.SETTINGS },
                onRunDemoTask = {
                    currentScreen = AniobScreen.CHAT
                    viewModel.submitTask("Open Settings")
                }
            )
        }
    }

    // Modal Grill-Me Clarification Sheet (AIM Phase 1.4)
    if (uiState.showGrillMeSheet && uiState.grillMeResult != null) {
        AniobGrillMeSheet(
            grillResult = uiState.grillMeResult!!,
            onConfirm = { answers -> viewModel.onGrillAnswersSubmitted(answers) },
            onDismiss = { viewModel.dismissGrillMe() }
        )
    }

    val confirmDialog = viewModel.showConfirmationDialog.value
    if (confirmDialog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.showConfirmationDialog.value = null },
            title = { Text("Confirmation Required") },
            text = { Text(confirmDialog.first) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = confirmDialog.second
                        viewModel.showConfirmationDialog.value = null
                        action()
                    },
                    modifier = Modifier.testTag("confirm_action_dialog_btn")
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.showConfirmationDialog.value = null },
                    modifier = Modifier.testTag("cancel_action_dialog_btn")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Addition A: Why this model? Explanation dialog
    if (showWhyModelDialog) {
        val installedModel = viewModel.getInstalledModelName() ?: "No local model installed"
        val lastReason = uiState.lastRoutingReason.ifBlank {
            "Policy: ${uiState.autoRouterMode}. Active local model: $installedModel. Runs 30-50 tok/s on CPU/GPU."
        }
        AlertDialog(
            onDismissRequest = { showWhyModelDialog = false },
            title = { Text("Active AI Execution Model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current Mode: ${uiState.autoRouterMode.uppercase()}", fontWeight = FontWeight.Bold)
                    Text("Model: $installedModel")
                    Text("Why this choice?\n$lastReason", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWhyModelDialog = false
                        currentScreen = AniobScreen.MODELS
                    }
                ) {
                    Text("Manage Models")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWhyModelDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }
}
