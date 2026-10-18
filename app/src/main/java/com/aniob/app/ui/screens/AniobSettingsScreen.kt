package com.aniob.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.ui.AniobUiState
import kotlinx.coroutines.launch

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
    onNavigateToTrust: () -> Unit = {},
    onSaveBaseUrl: (String) -> Unit = {},
    onTestConnection: (String, String, String) -> Unit = { _, _, _ -> },
    onLoadModels: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apiKeyInput by remember(uiState.omnirouteApiKey) { mutableStateOf(uiState.omnirouteApiKey) }
    var baseUrlInput by remember(uiState.omnirouteBaseUrl) { mutableStateOf(uiState.omnirouteBaseUrl) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember(uiState.omnirouteModel) { mutableStateOf(uiState.omnirouteModel.ifBlank { "auto" }) }
    var autoRouterMode by remember(uiState.autoRouterMode) { mutableStateOf(uiState.autoRouterMode) }
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
                        Text("Performance & Diagnostics Audit")
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

            // Cloud Provider Settings Section (OmniRoute Gateway)
            Card(modifier = Modifier.fillMaxWidth().testTag("omniroute_config_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isConfigured = uiState.omnirouteApiKey.isNotBlank() || apiKeyInput.isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OmniRoute Cloud API (Remote Reasoning)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Connect your OmniRoute gateway (375+ providers, 150+ free tiers, 16-factor auto-fallback).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Badge(
                            containerColor = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.testTag("omniroute_status_badge")
                        ) {
                            Text(
                                text = if (isConfigured) "Active ✓" else "Not set",
                                color = if (isConfigured) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Text(
                        text = "Gateway Server",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = baseUrlInput.isBlank(),
                            onClick = { baseUrlInput = "" },
                            label = { Text("Auto (Local + Cloud)") },
                            modifier = Modifier.testTag("chip_url_auto")
                        )
                        FilterChip(
                            selected = baseUrlInput == AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL,
                            onClick = { baseUrlInput = AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL },
                            label = {
                                Text(
                                    text = "Cloud (omniroute.online)"
                                )
                            },
                            modifier = Modifier.testTag("chip_url_cloud")
                        )
                        FilterChip(
                            selected = baseUrlInput == AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
                            onClick = { baseUrlInput = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL },
                            label = { Text("Local (:20128)") },
                            modifier = Modifier.testTag("chip_url_local")
                        )
                        FilterChip(
                            selected = baseUrlInput == AniobProviderConfig.OMNIROUTE_EMULATOR_BASE_URL,
                            onClick = { baseUrlInput = AniobProviderConfig.OMNIROUTE_EMULATOR_BASE_URL },
                            label = { Text("Emulator (:20128)") },
                            modifier = Modifier.testTag("chip_url_emulator")
                        )
                    }

                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("Gateway Server Address") },
                        placeholder = {
                            Text(
                                text = "${AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL} or ${AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL}"
                            )
                        },
                        trailingIcon = {
                            if (baseUrlInput.isNotEmpty()) {
                                IconButton(onClick = { baseUrlInput = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Reset server address")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_url"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Cloud Access Key") },
                        placeholder = { Text("sk-...") },
                        visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (apiKeyInput.isNotEmpty()) {
                                    IconButton(onClick = { apiKeyInput = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear key")
                                    }
                                }
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (apiKeyVisible) "Hide secret key" else "Reveal secret key"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_key"),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Target Model & Auto-Combo Strategy",
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(
                            onClick = { onLoadModels(apiKeyInput.trim(), baseUrlInput.trim()) },
                            modifier = Modifier.testTag("load_omniroute_models_button")
                        ) {
                            Text("Load Models", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedModel == "auto" || selectedModel.isBlank(),
                            onClick = { selectedModel = "auto" },
                            label = { Text("Auto") },
                            modifier = Modifier.testTag("chip_auto")
                        )
                        FilterChip(
                            selected = selectedModel == "auto/fast",
                            onClick = { selectedModel = "auto/fast" },
                            label = { Text("Fast") },
                            modifier = Modifier.testTag("chip_auto_fast")
                        )
                        FilterChip(
                            selected = selectedModel == "auto/smart",
                            onClick = { selectedModel = "auto/smart" },
                            label = { Text("Smart") },
                            modifier = Modifier.testTag("chip_auto_smart")
                        )
                        FilterChip(
                            selected = selectedModel == "auto/vision",
                            onClick = { selectedModel = "auto/vision" },
                            label = { Text("Vision") },
                            modifier = Modifier.testTag("chip_auto_vision")
                        )
                        FilterChip(
                            selected = selectedModel == "auto/coding",
                            onClick = { selectedModel = "auto/coding" },
                            label = { Text("Coding") },
                            modifier = Modifier.testTag("chip_auto_coding")
                        )
                        FilterChip(
                            selected = selectedModel == "auto/cheap",
                            onClick = { selectedModel = "auto/cheap" },
                            label = { Text("Free / Cheap") },
                            modifier = Modifier.testTag("chip_auto_cheap")
                        )
                        FilterChip(
                            selected = selectedModel == "gpt-4o" || selectedModel == "openai/gpt-4o",
                            onClick = { selectedModel = "openai/gpt-4o" },
                            label = { Text("gpt-4o") },
                            modifier = Modifier.testTag("chip_gpt4o")
                        )
                        FilterChip(
                            selected = selectedModel == "gpt-4o-mini" || selectedModel == "openai/gpt-4o-mini",
                            onClick = { selectedModel = "openai/gpt-4o-mini" },
                            label = { Text("gpt-4o-mini") },
                            modifier = Modifier.testTag("chip_gpt4o_mini")
                        )
                    }

                    if (uiState.omnirouteDiscoveredModels.isNotEmpty()) {
                        Text(
                            text = "Discovered Gateway Models (${uiState.omnirouteDiscoveredModels.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.omnirouteDiscoveredModels.take(20).forEach { modelId ->
                                FilterChip(
                                    selected = selectedModel == modelId,
                                    onClick = { selectedModel = modelId },
                                    label = { Text(modelId, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        label = { Text("Custom Model or Combo ID") },
                        placeholder = { Text("auto, auto/fast, kimi/kimi-k3, cc/claude-sonnet-4-6") },
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_model"),
                        singleLine = true
                    )

                    if (uiState.omnirouteConnectionStatus.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("omniroute_connection_status")
                        ) {
                            Text(
                                text = uiState.omnirouteConnectionStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val normalizedModel = selectedModel.ifBlank { "auto" }
                                onSaveBaseUrl(baseUrlInput.trim())
                                onSaveSettings(apiKeyInput.trim(), normalizedModel)
                                onTestConnection(apiKeyInput.trim(), baseUrlInput.trim(), normalizedModel)
                            },
                            enabled = !uiState.omnirouteTesting,
                            modifier = Modifier.weight(1f).testTag("test_omniroute_button")
                        ) {
                            Text(if (uiState.omnirouteTesting) "Testing..." else "Test Connection")
                        }
                        Button(
                            onClick = {
                                val normalizedModel = selectedModel.ifBlank { "auto" }
                                onSaveBaseUrl(baseUrlInput.trim())
                                onSaveSettings(apiKeyInput.trim(), normalizedModel)
                                Toast.makeText(context, "Cloud provider settings saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).testTag("save_settings_button")
                        ) {
                            Text("Save Provider Settings")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TextButton(onClick = { showAdvancedDetails = !showAdvancedDetails }) {
                            Text(if (showAdvancedDetails) "Hide Technical Details" else "Show Details")
                        }
                    }

                    if (showAdvancedDetails) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Gateway: ${AniobProviderConfig.resolveEndpoint(baseUrlInput)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text("Network: OpenAI-compatible SSE / ChatCompletions", style = MaterialTheme.typography.labelSmall)
                                Text("Protocol: HTTPS / JSON Streaming", style = MaterialTheme.typography.labelSmall)
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
                            text = "Model Engine: On-device LiteRT-LM (.litertlm, in-process)\nNo Ollama or localhost server required.",
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

            // Preferred Destinations & Browser
            val app = context.applicationContext as? com.aniob.app.AniobApplication
            Card(modifier = Modifier.fillMaxWidth().testTag("card_preferred_destinations")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preferred Destinations & Browser", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Customize default website builder and preferred Custom Tabs browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                app?.destinationPreferences?.clearAll()
                            }
                        },
                        modifier = Modifier.testTag("btn_reset_destinations")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset destination preferences")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
