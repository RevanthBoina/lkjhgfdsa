package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.AniobUiState

@Composable
fun AniobChatScreen(
    uiState: AniobUiState,
    onSubmitTask: (String) -> Unit,
    onStopTask: () -> Unit
) {
    var promptInput by remember { mutableStateOf("") }
    val isConnected = AniobAccessibilityService.isServiceConnected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth().testTag("service_status_card")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) "Accessibility Service Active" else "Accessibility Service Not Bound",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isConnected) "Ready to execute gestures & perceive UI" else "Enable 'Aniob' in Settings > Accessibility",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Badge {
                    Text(if (isConnected) "ONLINE" else "OFFLINE")
                }
            }
        }

        // Live Execution Status Banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().testTag("execution_status_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current State",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Provider: ${uiState.lastProviderUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (uiState.isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // Quick Command Suggestions
        Text(
            text = "Quick Command Presets",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip(
                onClick = {
                    promptInput = "Open Settings"
                    onSubmitTask("Open Settings")
                },
                label = { Text("Open Settings (M0)") },
                modifier = Modifier.testTag("preset_open_settings")
            )
            SuggestionChip(
                onClick = {
                    promptInput = "Book a cab"
                    onSubmitTask("Book a cab")
                },
                label = { Text("Book a cab (Grill-Me)") },
                modifier = Modifier.testTag("preset_book_cab")
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Natural Language Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                placeholder = { Text("Ask Aniob anything...") },
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
                            onSubmitTask(promptInput)
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
