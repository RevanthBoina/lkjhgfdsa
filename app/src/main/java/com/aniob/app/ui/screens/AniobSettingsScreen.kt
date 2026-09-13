package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.AniobUiState

@Composable
fun AniobSettingsScreen(
    uiState: AniobUiState,
    onSaveSettings: (String, String) -> Unit
) {
    var apiKeyInput by remember { mutableStateOf(uiState.omnirouteApiKey) }
    var selectedModel by remember { mutableStateOf(uiState.omnirouteModel) }
    var fastPathEnabled by remember { mutableStateOf(true) }
    var safetyGateEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Aniob Configuration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Omniroute Cloud Settings Section
        Card(modifier = Modifier.fillMaxWidth().testTag("omniroute_config_card")) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Omniroute Cloud (Primary Cloud Provider)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Base URL: https://api.omniroute.ai/v1/chat/completions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("OMNIROUTE_API_KEY") },
                    placeholder = { Text("sk-omniroute-...") },
                    modifier = Modifier.fillMaxWidth().testTag("input_omniroute_key"),
                    singleLine = true
                )

                Text(
                    text = "Target Model",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedModel == "gpt-4o",
                        onClick = { selectedModel = "gpt-4o" },
                        label = { Text("gpt-4o (Vision)") },
                        modifier = Modifier.testTag("chip_gpt4o")
                    )
                    FilterChip(
                        selected = selectedModel == "gpt-4o-mini",
                        onClick = { selectedModel = "gpt-4o-mini" },
                        label = { Text("gpt-4o-mini") },
                        modifier = Modifier.testTag("chip_gpt4o_mini")
                    )
                }

                Button(
                    onClick = { onSaveSettings(apiKeyInput, selectedModel) },
                    modifier = Modifier.align(Alignment.End).testTag("save_settings_button")
                ) {
                    Text("Save Provider Config")
                }
            }
        }

        // On-Device SLM & Optimization Settings
        Card(modifier = Modifier.fillMaxWidth().testTag("ondevice_config_card")) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "On-Device SLM (Local Provider)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Endpoint: http://127.0.0.1:11434/v1/chat/completions\nModel: Qwen2.5-1.5B (llama.cpp / Ollama)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("FastPath Replay Cache", fontWeight = FontWeight.SemiBold)
                        Text("0-token instant execution for repeat tasks", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = fastPathEnabled,
                        onCheckedChange = { fastPathEnabled = it },
                        modifier = Modifier.testTag("switch_fastpath")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AniobSafetyGate", fontWeight = FontWeight.SemiBold)
                        Text("Block unauthorized payments or destructive steps", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = safetyGateEnabled,
                        onCheckedChange = { safetyGateEnabled = it },
                        modifier = Modifier.testTag("switch_safety")
                    )
                }
            }
        }
    }
}
