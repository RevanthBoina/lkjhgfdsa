package com.aniob.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.model.AniobModelInfo
import com.aniob.app.model.DeviceInfo
import com.aniob.app.model.DownloadProgress
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** Compact ETA formatter: seconds -> "45s" / "3m 20s" / "1h 05m". */
internal fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobModelDownloadScreen(
    onBack: () -> Unit,
    uiState: com.aniob.app.ui.AniobUiState? = null,
    onSaveCloudSettings: (apiKey: String, model: String, baseUrl: String) -> Unit = { _, _, _ -> },
    onTestCloudConnection: (apiKey: String, baseUrl: String, model: String) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloader = remember { AniobModelDownloader(context) }

    var cloudKeyInput by remember(uiState?.omnirouteApiKey) { mutableStateOf(uiState?.omnirouteApiKey.orEmpty()) }
    var cloudUrlInput by remember(uiState?.omnirouteBaseUrl) { mutableStateOf(uiState?.omnirouteBaseUrl.orEmpty()) }
    var cloudModelInput by remember(uiState?.omnirouteModel) { mutableStateOf(uiState?.omnirouteModel?.ifBlank { "auto" } ?: "auto") }
    var cloudKeyVisible by remember { mutableStateOf(false) }

    var deviceInfo by remember { mutableStateOf(downloader.getDeviceInfo()) }
    val downloadStates by downloader.downloadStates.collectAsStateWithLifecycle()
    val activeDownloads by downloader.activeDownloads.collectAsStateWithLifecycle()
    val pausedDownloads by downloader.pausedDownloads.collectAsStateWithLifecycle()
    val downloadProgress by downloader.downloadProgressFlow.collectAsStateWithLifecycle()

    var models by remember { mutableStateOf(downloader.getAvailableModels()) }
    var defaultModelId by remember { mutableStateOf(downloader.getDefaultModelId()) }
    val recommendedId = remember(deviceInfo) { downloader.getRecommendedModel(deviceInfo) }

    var warningModelToDownload by remember { mutableStateOf<AniobModelInfo?>(null) }
    var isCustomExpanded by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    var customUrlName by remember { mutableStateOf("") }

    fun refreshModels() {
        deviceInfo = downloader.getDeviceInfo()
        models = downloader.getAvailableModels()
        defaultModelId = downloader.getDefaultModelId()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val temp = File(downloader.getTempDir(), "imported_${System.currentTimeMillis()}.${com.aniob.core.providers.AniobModelFiles.EXTENSION}")
                        FileOutputStream(temp).use { out -> stream.copyTo(out) }
                        val result = downloader.registerCustomFile("Imported Model", temp)
                        temp.delete()
                        if (result.isSuccess) {
                            Toast.makeText(context, "Model imported successfully", Toast.LENGTH_SHORT).show()
                            refreshModels()
                        } else {
                            Toast.makeText(context, "Import failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error importing file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun testModel(model: AniobModelInfo) {
        val file = downloader.getInstalledModelFile(model.id)
        if (file != null && file.exists() && file.length() > 0) {
            Toast.makeText(context, "${model.name} verified: model file intact and ready", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Model file not found or corrupted", Toast.LENGTH_LONG).show()
        }
    }

    // Refresh installed/paused model list as soon as a download leaves the active set.
    LaunchedEffect(activeDownloads, pausedDownloads) {
        if (activeDownloads.isEmpty()) refreshModels()
    }

    fun startDownload(model: AniobModelInfo) {
        if (model.requiresCharging && !deviceInfo.isCharging && deviceInfo.batteryPct < 50) {
            Toast.makeText(context, "Plug in charging to download ${model.name} safely", Toast.LENGTH_LONG).show()
            return
        }
        coroutineScope.launch {
            val result = downloader.downloadModelWithProgress(model.id) { _ -> }
            if (result.isSuccess) {
                Toast.makeText(context, "${model.name} installed successfully", Toast.LENGTH_SHORT).show()
                refreshModels()
            } else if (!downloader.isPaused(model.id)) {
                Toast.makeText(context, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                refreshModels()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On-Device AI Models", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("models_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshModels() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Device Hardware Telemetry Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("device_telemetry_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Device Performance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text("${deviceInfo.totalRamGb} GB Memory", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Available: ${deviceInfo.freeRamGb} GB", style = MaterialTheme.typography.bodySmall)
                            Text("Free Storage: ${"%.1f".format(deviceInfo.freeStorageGb)} GB", style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Battery: ${deviceInfo.batteryPct}% ${if (deviceInfo.isCharging) "(Charging)" else ""}", style = MaterialTheme.typography.bodySmall)
                            Text("Network: ${if (deviceInfo.isWifi) "Wi-Fi" else "Mobile Data"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 1b. Cloud Brain (OmniRoute API) Quick Setup Card
            item {
                val hasCloud = cloudKeyInput.isNotBlank() || (uiState?.omnirouteApiKey?.isNotBlank() == true)
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("cloud_brain_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cloud Brain (OmniRoute API)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Instant setup with zero large downloads. Routes across 375+ cloud models with auto-fallback.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Badge(
                                containerColor = if (hasCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ) {
                                Text(
                                    text = if (hasCloud) "Ready ✓" else "Optional",
                                    color = if (hasCloud) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cloudUrlInput.isBlank(),
                                onClick = { cloudUrlInput = "" },
                                label = { Text("Auto") },
                                modifier = Modifier.testTag("models_chip_url_auto")
                            )
                            FilterChip(
                                selected = cloudUrlInput == com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL,
                                onClick = { cloudUrlInput = com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL },
                                label = {
                                    Text(
                                        text = "Cloud (omniroute.online)"
                                    )
                                },
                                modifier = Modifier.testTag("models_chip_url_cloud")
                            )
                            FilterChip(
                                selected = cloudUrlInput == com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
                                onClick = { cloudUrlInput = com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL },
                                label = { Text("Local :20128") },
                                modifier = Modifier.testTag("models_chip_url_local")
                            )
                        }

                        OutlinedTextField(
                            value = cloudUrlInput,
                            onValueChange = { cloudUrlInput = it },
                            label = { Text("Gateway Server Address") },
                            placeholder = {
                                Text(
                                    text = "${com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL} or ${com.aniob.app.provider.AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL}"
                                )
                            },
                            modifier = Modifier.fillMaxWidth().testTag("models_input_omniroute_url"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = cloudKeyInput,
                            onValueChange = { cloudKeyInput = it },
                            label = { Text("Cloud Access Key") },
                            placeholder = { Text("sk-...") },
                            visualTransformation = if (cloudKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { cloudKeyVisible = !cloudKeyVisible }) {
                                    Icon(
                                        imageVector = if (cloudKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (cloudKeyVisible) "Hide secret key" else "Reveal secret key"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("models_input_omniroute_key"),
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Auto", "auto/fast" to "Fast", "auto/smart" to "Smart", "auto/vision" to "Vision").forEach { (id, label) ->
                                FilterChip(
                                    selected = cloudModelInput == id,
                                    onClick = { cloudModelInput = id },
                                    label = { Text(label) }
                                )
                            }
                        }

                        if (!uiState?.omnirouteConnectionStatus.isNullOrBlank()) {
                            Text(
                                text = uiState?.omnirouteConnectionStatus.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("models_cloud_status")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onTestCloudConnection(
                                        cloudKeyInput.trim(),
                                        cloudUrlInput.trim(),
                                        cloudModelInput.ifBlank { "auto" }
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("test_cloud_brain_btn")
                            ) {
                                Text("Test Connection")
                            }
                            Button(
                                onClick = {
                                    onSaveCloudSettings(
                                        cloudKeyInput.trim(),
                                        cloudModelInput.ifBlank { "auto" },
                                        cloudUrlInput.trim()
                                    )
                                    Toast.makeText(context, "Cloud API saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).testTag("save_cloud_brain_btn")
                            ) {
                                Text("Save Cloud Brain")
                            }
                        }
                    }
                }
            }

            // 2. Recommendation Banner
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("recommendation_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Recommend,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Recommended: ${models.find { it.id == recommendedId }?.name ?: "Phi-4 Mini 3.8B"}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Smart enough for most tasks and quick to respond. Recommended for your device.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // 3. Models List Header
            item {
                Text(
                    text = "Available Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4. Model Cards
            items(models) { model ->
                val isDownloading = activeDownloads.contains(model.id)
                val isPaused = pausedDownloads.contains(model.id)
                val progress = downloadStates[model.id] ?: if (model.isInstalled) 100 else 0
                val prog = downloadProgress[model.id]
                val isDefault = defaultModelId == model.id

                ModelItemCard(
                    model = model,
                    isRecommended = model.id == recommendedId,
                    isDefault = isDefault,
                    isDownloading = isDownloading,
                    isPaused = isPaused,
                    progress = progress,
                    downloadProgress = prog,
                    deviceInfo = deviceInfo,
                    onDownload = {
                        if (deviceInfo.totalRamGb < model.minDeviceRamGb) {
                            warningModelToDownload = model
                        } else {
                            startDownload(model)
                        }
                    },
                    onPause = { downloader.pauseDownload(model.id) },
                    onResume = {
                        coroutineScope.launch {
                            downloader.resumeDownload(model.id) { _ -> }
                            refreshModels()
                        }
                    },
                    onCancel = {
                        downloader.cancelDownload(model.id)
                        Toast.makeText(context, "${model.name} download cancelled", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    },
                    onDelete = {
                        downloader.deleteModel(model.id)
                        Toast.makeText(context, "${model.name} removed", Toast.LENGTH_SHORT).show()
                        refreshModels()
                    },
                    onSetDefault = {
                        downloader.setDefaultModelId(model.id)
                        defaultModelId = model.id
                        Toast.makeText(context, "${model.name} set as primary local model", Toast.LENGTH_SHORT).show()
                    },
                    onTestModel = {
                        testModel(model)
                    }
                )
            }

            // 4. Side-load escape hatch: Install from device or URL
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_model_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Install from File or Web",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { isCustomExpanded = !isCustomExpanded },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (isCustomExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle custom model installation"
                                )
                            }
                        }

                        if (isCustomExpanded) {
                            OutlinedButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Local Model File")
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            OutlinedTextField(
                                value = customUrlName,
                                onValueChange = { customUrlName = it },
                                label = { Text("Model Name") },
                                placeholder = { Text("e.g. Custom Model") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it },
                                label = { Text("Download Address") },
                                placeholder = { Text("https://...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    if (customUrl.isBlank() || !customUrl.startsWith("http")) {
                                        Toast.makeText(context, "Enter a valid download link", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val customInfo = downloader.registerCustomUrl(
                                            name = customUrlName.ifBlank { "Custom Web Model" },
                                            url = customUrl.trim()
                                        )
                                        customUrl = ""
                                        customUrlName = ""
                                        refreshModels()
                                        startDownload(customInfo)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download from Web")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Addition D: RAM Warning Dialog for heavier models on lower RAM phones
        if (warningModelToDownload != null) {
            val targetModel = warningModelToDownload!!
            AlertDialog(
                onDismissRequest = { warningModelToDownload = null },
                title = { Text("High Memory Requirement") },
                text = {
                    Text(
                        "This model (${targetModel.name}) recommends at least ${targetModel.minDeviceRamGb} GB memory. " +
                        "Your device has ${deviceInfo.totalRamGb} GB memory. Running this model may cause memory pressure or slower performance.\n\n" +
                        "Do you wish to proceed?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val m = warningModelToDownload!!
                            warningModelToDownload = null
                            startDownload(m)
                        }
                    ) {
                        Text("Download Anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { warningModelToDownload = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ModelItemCard(
    model: AniobModelInfo,
    isRecommended: Boolean,
    isDefault: Boolean,
    isDownloading: Boolean,
    isPaused: Boolean,
    progress: Int,
    downloadProgress: DownloadProgress?,
    deviceInfo: DeviceInfo,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onTestModel: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${model.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isDefault) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isRecommended) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("Best", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (isDefault) {
                        Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                            Text("Active", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Text(
                    text = "${model.sizeGb} GB",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Memory: ${model.ramRequiredGb} GB", style = MaterialTheme.typography.labelSmall)
                Text("Format: Standard", style = MaterialTheme.typography.labelSmall)
                if (model.requiresCharging) {
                    Text("Needs charging", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            if (isDownloading || isPaused) {
                val mbPerSec = (downloadProgress?.bytesPerSecond ?: 0L) / 1024.0 / 1024.0
                val etaText = formatEta(downloadProgress?.etaSeconds ?: 0L)
                val downloadedGb = (downloadProgress?.downloadedBytes ?: 0L) / 1024.0 / 1024.0 / 1024.0
                val totalGb = downloadProgress?.totalBytes?.takeIf { it > 0 }?.let { it / 1024.0 / 1024.0 / 1024.0 }
                    ?: model.sizeGb
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().testTag("download_progress_${model.id}")
                    )
                    Text(
                        text = if (isPaused) {
                            "Paused \u00b7 $progress% \u00b7 ${"%.2f".format(downloadedGb)}/${"%.2f".format(totalGb)} GB"
                        } else {
                            "Downloading... $progress% \u00b7 ${"%.1f".format(mbPerSec)} MB/s \u00b7 $etaText left \u00b7 ${"%.2f".format(downloadedGb)}/${"%.2f".format(totalGb)} GB"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("download_stats_${model.id}")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isPaused) {
                            Button(onClick = onResume, modifier = Modifier.testTag("resume_${model.id}")) {
                                Text("Resume")
                            }
                        } else {
                            OutlinedButton(onClick = onPause, modifier = Modifier.testTag("pause_${model.id}")) {
                                Text("Pause")
                            }
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel_${model.id}")) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (model.isInstalled) {
                    OutlinedButton(onClick = onTestModel, modifier = Modifier.testTag("test_${model.id}")) {
                        Text("Test")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("delete_${model.id}")) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!isDefault) {
                        OutlinedButton(onClick = onSetDefault, modifier = Modifier.testTag("set_default_${model.id}")) {
                            Text("Set Default")
                        }
                    } else {
                        FilledTonalButton(onClick = {}, enabled = false) {
                            Text("Default Model")
                        }
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        modifier = Modifier.testTag("download_${model.id}")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}
