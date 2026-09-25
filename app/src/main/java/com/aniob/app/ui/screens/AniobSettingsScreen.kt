package com.aniob.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onAutoRouterModeChanged: (String) -> Unit = {},
    onFastPathChanged: (Boolean) -> Unit = {},
    onSafetyGateChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToOnboarding: () -> Unit = {},
    onNavigateToTrust: () -> Unit = {}
) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf(uiState.omnirouteApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(uiState.omnirouteModel) }
    var autoRouterMode by remember { mutableStateOf(uiState.autoRouterMode) }
    var showAdvancedDetails by remember { mutableStateOf(false) }

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
            // AutoRouter Mode Section (Phase 3.3)
            Card(modifier = Modifier.fillMaxWidth().testTag("card_autorouter_mode")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "AutoRouter Execution Policy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Controls whether tasks execute locally on-device or escalate to Omniroute cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val modes = listOf(
                        Triple("auto", "Auto (Recommended)", "Smart local-first, cloud when battery/vision requires"),
                        Triple("local-first", "Local-First", "Prefer on-device, cloud only for vision/complex"),
                        Triple("local-only", "Local-Only", "Never use cloud, offline mode, fail if impossible"),
                        Triple("cloud-only", "Cloud-Only", "Always use cloud (Omniroute)"),
                        Triple("balanced", "Balanced", "Local for simple tasks, cloud for research/creative")
                    )

                    modes.forEach { (modeKey, modeTitle, modeDesc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoRouterMode = modeKey
                                    onAutoRouterModeChanged(modeKey)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (autoRouterMode == modeKey),
                                onClick = {
                                    autoRouterMode = modeKey
                                    onAutoRouterModeChanged(modeKey)
                                },
                                modifier = Modifier.testTag("radio_mode_$modeKey")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(modeTitle, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(modeDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
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
                        visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (apiKeyVisible) "Hide API Key" else "Reveal API Key"
                                )
                            }
                        },
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showAdvancedDetails = !showAdvancedDetails }) {
                            Text(if (showAdvancedDetails) "Hide Technical Details" else "Show Details")
                        }
                        Button(
                            onClick = { onSaveSettings(apiKeyInput, selectedModel) },
                            modifier = Modifier.testTag("save_settings_button")
                        ) {
                            Text("Save Provider Config")
                        }
                    }

                    if (showAdvancedDetails) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Endpoint: https://api.omniroute.ai/v1/chat/completions", style = MaterialTheme.typography.labelSmall)
                                Text("Protocol: OpenAI-compatible SSE / ChatCompletions", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
                        text = "On-Device SLM & Automation Policy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (showAdvancedDetails) {
                        Text(
                            text = "Endpoint: http://127.0.0.1:11434/v1/chat/completions\nModel Engine: On-device GGUF / LiteRT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reuse verified tasks", fontWeight = FontWeight.SemiBold)
                            Text("0-token instant execution for previously verified workflows", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.fastPathEnabled,
                            onCheckedChange = { onFastPathChanged(it) },
                            modifier = Modifier.testTag("switch_fastpath")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ask before actions", fontWeight = FontWeight.SemiBold)
                            Text("Require approval before sensitive or high-risk actions", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.safetyGateEnabled,
                            onCheckedChange = { onSafetyGateChanged(it) },
                            modifier = Modifier.testTag("switch_safety")
                        )
                    }

                    OutlinedButton(
                        onClick = onNavigateToTrust,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_trust")
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Authoritative Safety & Policy Controls")
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
