package com.aniob.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.aniob.app.ui.AniobUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobSettingsScreen(
    uiState: AniobUiState,
    onSaveSettings: (String, String) -> Unit,
    onBack: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf(uiState.omnirouteApiKey) }
    var selectedModel by remember { mutableStateOf(uiState.omnirouteModel) }
    var fastPathEnabled by remember { mutableStateOf(true) }
    var safetyGateEnabled by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick Navigation Shortcuts Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("System & Performance", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                    OutlinedButton(
                        onClick = onNavigateToModels,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_models")
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("On-Device AI Models (Phi-4, Gemma, Llama)")
                    }

                    OutlinedButton(
                        onClick = onNavigateToStats,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_stats")
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Performance & Telemetry Audit")
                    }

                    OutlinedButton(
                        onClick = onNavigateToOnboarding,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_onboarding")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Setup & Permissions Checklist")
                    }
                }
            }

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
                        text = "Endpoint: http://127.0.0.1:11434/v1/chat/completions\nModel: On-device GGUF / LiteRT",
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

            // OEM Battery Optimization deep-link (Phase 1.7)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Battery & Background Resilience", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Disable battery optimization so Xiaomi, Samsung, OnePlus do not terminate Aniob mid-execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.align(Alignment.End).testTag("battery_deep_link_btn")
                    ) {
                        Text("Open Battery Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
