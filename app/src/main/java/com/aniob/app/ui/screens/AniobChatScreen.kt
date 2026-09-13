package com.aniob.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.AniobUiState
import com.aniob.app.ui.chat.ChatMessage

/**
 * Primary Chat Screen.
 * Features:
 * - Live ticker: "● Running · step 4" + "[Details] -> TrackerSheet"
 * - Tracker Sheet open = 0 model calls, 0 captures (queries local in-memory records only)
 * - Conversation timeline with @Immutable items and stable keys (key = { it.id })
 * - FastPath replay with fingerprint verification before each tap
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobChatScreen(
    uiState: AniobUiState,
    onSubmitTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onShowTrackerSheet: (Boolean) -> Unit,
    onFilterChanged: (String) -> Unit
) {
    var promptInput by remember { mutableStateOf("") }
    val isConnected = AniobAccessibilityService.isServiceConnected

    // Tracker Bottom Sheet (0 model calls, 0 captures)
    if (uiState.showTrackerSheet) {
        ModalBottomSheet(
            onDismissRequest = { onShowTrackerSheet(false) },
            modifier = Modifier.testTag("tracker_bottom_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(16.dp)
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
                    TextButton(
                        onClick = { onShowTrackerSheet(false) },
                        modifier = Modifier.testTag("close_tracker_sheet")
                    ) {
                        Text("Close")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status & Connectivity Ribbon
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("service_status_card")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) "Accessibility Active" else "Accessibility Disconnected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isConnected) "Agent ready to perceive & actuate" else "Enable 'Aniob' in Android Settings > Accessibility",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Badge {
                    Text(if (isConnected) "READY" else "OFFLINE")
                }
            }
        }

        // Live Execution Ticker: "● Running · step 4" + "[Details] -> TrackerSheet"
        AnimatedVisibility(visible = uiState.isRunning) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_execution_ticker")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pulsing Green Indicator
                        PulsingDot()
                        Text(
                            text = "Running · step ${uiState.currentStep}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "(${uiState.lastProviderUsed})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = { onShowTrackerSheet(true) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("open_tracker_sheet_btn")
                    ) {
                        Text("Details")
                    }
                }
            }
        }

        // Quick Preset Suggestions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip(
                onClick = {
                    promptInput = "Open Settings"
                    onSubmitTask("Open Settings")
                },
                label = { Text("Open Settings") },
                modifier = Modifier.testTag("preset_open_settings")
            )
            SuggestionChip(
                onClick = {
                    promptInput = "Book a cab"
                    onSubmitTask("Book a cab")
                },
                label = { Text("Book a cab") },
                modifier = Modifier.testTag("preset_book_cab")
            )
            SuggestionChip(
                onClick = {
                    promptInput = "Send a text"
                    onSubmitTask("Send a text")
                },
                label = { Text("Send a text") },
                modifier = Modifier.testTag("preset_send_message")
            )
        }

        // Conversation History (LazyColumn with stable keys)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("chat_timeline"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = false
        ) {
            items(
                items = uiState.chatMessages,
                key = { it.id }
            ) { message ->
                ChatBubble(message)
            }

            // Typing / Streaming bubble
            if (uiState.isStreaming && uiState.streamingBubbleText.isNotBlank()) {
                item(key = "streaming_bubble") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = uiState.streamingBubbleText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        // Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                placeholder = { Text("Ask Aniob to automate anything...") },
                enabled = !uiState.isRunning,
                singleLine = true
            )

            if (uiState.isRunning) {
                IconButton(
                    onClick = onStopTask,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Task",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (promptInput.isNotBlank()) {
                            val cmd = promptInput
                            promptInput = ""
                            onSubmitTask(cmd)
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("submit_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Run Task",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
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
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFF00C853).copy(alpha = alpha))
    )
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .testTag("chat_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.stepIndex != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Completed in ${message.stepIndex} steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
