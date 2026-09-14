package com.aniob.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.service.AniobAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobOnboardingScreen(
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRunDemoTask: () -> Unit
) {
    val context = LocalContext.current
    var isAccessibilityActive by remember { mutableStateOf(AniobAccessibilityService.isServiceConnected) }
    var hasOverlayPermission by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true)
    }

    fun refreshPermissions() {
        isAccessibilityActive = AniobAccessibilityService.isServiceConnected
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup & Permissions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("onboarding_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshPermissions() }) {
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Step 1: Welcome
            item {
                OnboardingStepCard(
                    stepNumber = 1,
                    title = "Welcome to Aniob",
                    description = "Aniob is an autonomous Android UI agent that perceives on-screen UI hierarchies and actuates actions on your behalf.",
                    icon = Icons.Default.SmartToy,
                    isComplete = true,
                    actionText = null,
                    onAction = null
                )
            }

            // Step 2: Accessibility Service
            item {
                OnboardingStepCard(
                    stepNumber = 2,
                    title = "Accessibility Automation",
                    description = "Required to inspect UI nodes, click buttons, and enter text on active apps.",
                    icon = Icons.Default.AccessibilityNew,
                    isComplete = isAccessibilityActive,
                    actionText = if (isAccessibilityActive) "Enabled" else "Open Accessibility Settings",
                    onAction = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // Step 3: Draw Over Other Apps
            item {
                OnboardingStepCard(
                    stepNumber = 3,
                    title = "Display Over Other Apps",
                    description = "Allows the minimal 32dp execution pill and safety confirmation overlay to float while tasks run.",
                    icon = Icons.Default.Layers,
                    isComplete = hasOverlayPermission,
                    actionText = if (hasOverlayPermission) "Granted" else "Grant Overlay Permission",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            context.startActivity(intent)
                        }
                    }
                )
            }

            // Step 4: Notification Permission
            item {
                OnboardingStepCard(
                    stepNumber = 4,
                    title = "Notifications",
                    description = "Keeps foreground service alive and posts progress updates during long-running tasks.",
                    icon = Icons.Default.Notifications,
                    isComplete = true,
                    actionText = "App Notification Settings",
                    onAction = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // Step 5: OEM Battery Optimization
            item {
                OnboardingStepCard(
                    stepNumber = 5,
                    title = "Battery Unrestricted (OEM)",
                    description = "Prevents aggressive background killing by Xiaomi, Samsung, OnePlus, or Oppo battery managers.",
                    icon = Icons.Default.BatteryChargingFull,
                    isComplete = false,
                    actionText = "Disable Battery Restriction",
                    onAction = {
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
                    }
                )
            }

            // Step 6: Provider Setup
            item {
                OnboardingStepCard(
                    stepNumber = 6,
                    title = "Provider Configuration",
                    description = "Choose between local on-device SLM models (Phi-4, Gemma 3) or cloud reasoning (Omniroute).",
                    icon = Icons.Default.Memory,
                    isComplete = true,
                    actionText = "Configure Models & Keys",
                    onAction = onNavigateToModels
                )
            }

            // Step 7: Demo Task
            item {
                OnboardingStepCard(
                    stepNumber = 7,
                    title = "M0 Validation Demo",
                    description = "Run a verification benchmark: 'Open Android Settings' to confirm end-to-end perception and execution.",
                    icon = Icons.Default.PlayArrow,
                    isComplete = false,
                    actionText = "Run 'Open Settings' Demo",
                    onAction = onRunDemoTask
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun OnboardingStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    icon: ImageVector,
    isComplete: Boolean,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("onboarding_step_$stepNumber"),
        colors = CardDefaults.cardColors(
            containerColor = if (isComplete) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Step $stepNumber: $title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isComplete) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (actionText != null && onAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isComplete) {
                        OutlinedButton(onClick = onAction, modifier = Modifier.testTag("onboarding_action_$stepNumber")) {
                            Text(actionText)
                        }
                    } else {
                        Button(onClick = onAction, modifier = Modifier.testTag("onboarding_action_$stepNumber")) {
                            Text(actionText)
                        }
                    }
                }
            }
        }
    }
}
