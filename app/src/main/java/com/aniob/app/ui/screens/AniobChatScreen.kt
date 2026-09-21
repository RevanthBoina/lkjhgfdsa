package com.aniob.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.system.SystemState
import com.aniob.app.ui.AniobUiState
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.*
import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.narration.FailureReasonCopy
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobChatScreen(
    uiState: AniobUiState,
    systemState: SystemState? = null,
    onSubmitTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onPauseToggle: () -> Unit = {},
    onShowTrackerSheet: (Boolean) -> Unit,
    onFilterChanged: (String) -> Unit,
    onManageModels: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onDismissInterrupted: () -> Unit = {},
    onDismissRecovery: () -> Unit = {},
    onRunAgain: (String) -> Unit = {},
    onViewSteps: () -> Unit = {},
    onMomentDismiss: () -> Unit = {},
    renderWindow: () -> List<ChatMessage> = { emptyList() }
) {
    val context = LocalContext.current
    var promptInput by rememberSaveable { mutableStateOf("") }
    val isConnected = systemState?.a11yConnected ?: AniobAccessibilityService.isServiceConnected
    var bannerDismissed by remember { mutableStateOf(false) }

    val windowMessages = renderWindow()
    val displayMessages: List<ChatMessage> = if (windowMessages.isNotEmpty()) windowMessages else uiState.chatMessages

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                promptInput = spokenText
            }
        }
    }
    val speechAvailable = remember(context) {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    val listState = rememberLazyListState()

    // Auto-scroll to latest message
    LaunchedEffect(uiState.chatMessages.size, uiState.streamingBubbleText) {
        if (uiState.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.chatMessages.size - 1)
        }
    }

    // Tracker Bottom Sheet (0 model calls, 0 captures)
    if (uiState.showTrackerSheet) {
        ModalBottomSheet(
            onDismissRequest = { onShowTrackerSheet(false) },
            modifier = Modifier.testTag("tracker_bottom_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Execution Steps (Local Zero-Capture)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { onShowTrackerSheet(false) },
                        modifier = Modifier.testTag("close_tracker_sheet")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                AniobTrackerScreen(
                    uiState = uiState,
                    onFilterChanged = onFilterChanged
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Permission Health Banner (Blocking card only when disconnected, not shown everyday)
        if (!isConnected && !bannerDismissed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("service_status_card")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accessibility Service Disabled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Enable Aniob in Android Settings to automate apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("enable_a11y_button")
                    ) {
                        Text("Enable")
                    }
                    IconButton(onClick = { bannerDismissed = true }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 2. Interrupted recovery banner (UX-6)
        if (uiState.interruptedSummary != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Task was interrupted", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text("\"${uiState.interruptedSummary.prompt}\" stopped when Aniob restarted.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val p = uiState.interruptedSummary.prompt
                            onDismissInterrupted()
                            onSubmitTask(p)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Resume")
                    }
                    IconButton(onClick = onDismissInterrupted) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 3. Setup reminder banner if skipped (PROMPT 4)
        if (systemState != null && (!systemState.a11yConnected || !systemState.overlayGranted) && isConnected && !bannerDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpenSetup() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Finish setup to unlock all features →", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onOpenSetup, contentPadding = PaddingValues(0.dp)) {
                        Text("Finish setup", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 4. Live Cockpit Narration Ticker (PROMPT 3)
        AnimatedVisibility(visible = uiState.isRunning) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .testTag("live_execution_ticker")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PulsingDot()
                        val narration = uiState.lastNarration.ifBlank {
                            if (uiState.isPaused) "Paused — do this step yourself, then Resume"
                            else "Step ${uiState.currentStep} · ${FailureReasonCopy.providerLabel(uiState.lastProviderUsed)}"
                        }
                        Text(
                            text = narration,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPauseToggle,
                            modifier = Modifier.size(32.dp).testTag("ticker_pause_btn")
                        ) {
                            Icon(
                                if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (uiState.isPaused) "Resume" else "Pause",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        TextButton(
                            onClick = { onShowTrackerSheet(true) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("open_tracker_sheet_btn")
                        ) {
                            Text("Details", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(
                            onClick = onStopTask,
                            modifier = Modifier.size(32.dp).testTag("ticker_stop_btn")
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Conversation History or Empty State
        if (displayMessages.isEmpty() && uiState.lastSummary == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "How can I help you today?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tell Aniob what to do, or tap a starter task below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                ExampleTaskRow(title = "Open Settings", subtitle = "Safe system navigation") {
                    promptInput = "Open Settings"
                    onSubmitTask("Open Settings")
                }
                ExampleTaskRow(title = "Check battery status", subtitle = "Reads system power info") {
                    promptInput = "Check battery status"
                    onSubmitTask("Check battery status")
                }
                ExampleTaskRow(title = "Book a cab to airport", subtitle = "Shows Grill-Me clarification") {
                    promptInput = "Book a cab to airport"
                    onSubmitTask("Book a cab to airport")
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("chat_timeline"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = displayMessages,
                    key = { it.id }
                ) { message ->
                    WhatsAppChatBubble(
                        message = message,
                        onOpenDetails = { onShowTrackerSheet(true) },
                        onManageModels = onManageModels,
                        onRetry = {
                            if (!message.retryPrompt.isNullOrBlank()) {
                                onSubmitTask(message.retryPrompt)
                            }
                        }
                    )
                }

                if (uiState.isStreaming && uiState.streamingBubbleText.isNotBlank()) {
                    item(key = "streaming_bubble") {
                        StreamingChatBubble(uiState.streamingBubbleText)
                    }
                }

                if (uiState.lastSummary != null) {
                    item(key = "task_summary_card") {
                        TaskSummaryCard(
                            summary = uiState.lastSummary,
                            onRunAgain = { onRunAgain(uiState.lastSummary.prompt) },
                            onViewSteps = onViewSteps,
                            onMakeSkill = { onOpenSkills() },
                            onRetry = { onSubmitTask(uiState.lastSummary.prompt) }
                        )
                    }
                }
            }
        }

        // 6. Queued task chip (PROMPT 2)
        if (uiState.isRunning && uiState.queuedPrompt != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "After this task: \"${uiState.queuedPrompt}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSubmitTask("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel queued task", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 7. Horizontal Suggestion Rail (PROMPT 2)
        val catalogApps: List<com.aniob.core.knowledge.AppCatalogEntry> = remember {
            runCatching {
                AniobAppCatalog.getInstance().getAll().take(3)
            }.getOrDefault(emptyList())
        }
        val recentPrompts = remember(uiState.sessionScores) {
            uiState.sessionScores.map { it.userPrompt }.filter { it.isNotBlank() }.distinct().take(5)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            catalogApps.forEach { app ->
                val appPrompt = "Open ${app.appName}"
                SuggestionChip(
                    onClick = {
                        if (promptInput == appPrompt) {
                            onSubmitTask(appPrompt)
                            promptInput = ""
                        } else {
                            promptInput = appPrompt
                        }
                    },
                    label = { Text(appPrompt, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("suggestion_app_${app.packageName}")
                )
            }

            listOf("Search in YouTube", "Send message to", "Set alarm for").forEach { template ->
                SuggestionChip(
                    onClick = {
                        if (promptInput != "$template ") {
                            promptInput = "$template "
                        }
                    },
                    label = { Text(template, style = MaterialTheme.typography.labelSmall) }
                )
            }

            recentPrompts.forEach { recent ->
                SuggestionChip(
                    onClick = {
                        if (promptInput == recent) {
                            onSubmitTask(recent)
                            promptInput = ""
                        } else {
                            promptInput = recent
                        }
                    },
                    label = { Text(recent.take(24), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // 8. Input Composer (PROMPT 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                placeholder = {
                    Text(if (uiState.isRunning) "Queue next task..." else "Message or command...")
                },
                shape = RoundedCornerShape(24.dp),
                enabled = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (promptInput.isNotBlank()) {
                            val cmd = promptInput.trim()
                            promptInput = ""
                            onSubmitTask(cmd)
                        }
                    }
                )
            )

            if (speechAvailable) {
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your task to Aniob")
                        }
                        runCatching { speechLauncher.launch(intent) }
                    },
                    modifier = Modifier.testTag("mic_button")
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (uiState.isRunning && promptInput.isBlank()) {
                FilledIconButton(
                    onClick = onStopTask,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Task",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            } else {
                FilledIconButton(
                    onClick = {
                        if (promptInput.isNotBlank()) {
                            val cmd = promptInput.trim()
                            promptInput = ""
                            onSubmitTask(cmd)
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("submit_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isRunning) Icons.Default.Queue else Icons.Default.Send,
                        contentDescription = if (uiState.isRunning) "Queue" else "Send",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun ExampleTaskRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun WhatsAppChatBubble(
    message: ChatMessage,
    onOpenDetails: () -> Unit,
    onManageModels: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val timeString = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    val isPreflightFailure = message.badge?.contains("Pre-flight") == true

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .testTag("chat_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isPreflightFailure) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.content.contains("install an on-device model", ignoreCase = true) ||
                            message.content.contains("Local model file missing", ignoreCase = true)
                        ) {
                            Button(
                                onClick = onManageModels,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_manage_models_btn")
                            ) {
                                Text("Manage Models", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (message.content.contains("Accessibility Service Disabled", ignoreCase = true)) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_enable_a11y_btn")
                            ) {
                                Text("Enable", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (!message.retryPrompt.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_retry_btn")
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (message.badge != null) {
                        Text(
                            text = message.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (message.stepIndex != null) {
                        Text(
                            text = "${message.stepIndex} steps · ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!isUser && message.role == "assistant" && message.stepIndex != null) {
                        Text(
                            text = "[Details]",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenDetails() }
                        )
                    }

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingChatBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulsingDot()
                    Text("Generating...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF00C853).copy(alpha = alpha))
    )
}

/**
 * Task completion proof card (PROMPT 5).
 * Renders checklist of what was verified with real checkmarks (✓/✗), honest stats, and action buttons.
 */
@Composable
fun TaskSummaryCard(
    summary: TaskSummary,
    onRunAgain: () -> Unit,
    onViewSteps: () -> Unit,
    onMakeSkill: () -> Unit,
    onRetry: () -> Unit
) {
    val isSuccess = summary.outcome == Outcome.SUCCESS
    val isStopped = summary.outcome == Outcome.STOPPED
    val containerColor = when {
        isSuccess -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        isStopped -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("task_summary_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = when {
                        isSuccess -> Icons.Default.CheckCircle
                        isStopped -> Icons.Default.StopCircle
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = when {
                        isSuccess -> MaterialTheme.colorScheme.primary
                        isStopped -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = when {
                        isSuccess -> "Task Completed"
                        isStopped -> "Stopped by you at step ${summary.steps}"
                        else -> "Task Failed"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (summary.evidence.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary.evidence.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (item.met) "✓" else "✗",
                                fontWeight = FontWeight.Bold,
                                color = if (item.met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            val providerLabel = FailureReasonCopy.providerLabel(summary.provider)
            val durationSec = summary.durationMs / 1000.0
            Text(
                text = "${summary.steps} steps · ${String.format(Locale.US, "%.1fs", durationSec)} · $providerLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isSuccess && !isStopped && !summary.reason.isNullOrBlank()) {
                Text(
                    text = FailureReasonCopy.humanize(summary.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSuccess) {
                    Button(
                        onClick = onRunAgain,
                        modifier = Modifier.weight(1f).testTag("summary_run_again")
                    ) {
                        Text("Run again", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onViewSteps,
                        modifier = Modifier.weight(1f).testTag("summary_view_steps")
                    ) {
                        Text("View steps", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onMakeSkill,
                        modifier = Modifier.weight(1f).testTag("summary_make_skill")
                    ) {
                        Text("Make skill", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (!isStopped) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f).testTag("summary_retry")
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onViewSteps,
                        modifier = Modifier.weight(1f).testTag("summary_view_steps")
                    ) {
                        Text("View steps", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
