package com.aniob.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.system.SystemState

/**
 * Setup Concierge (UX-1).
 *
 * Two modes over the same cards: WIZARD (first run) and CHECKLIST (menu entry). Every state read
 * comes from the reactive [SystemState], so a step auto-completes when the user returns from
 * Settings — the manual Refresh button is only a secondary affordance.
 *
 * The simulated demo step needs zero permissions: desire is built before any ask.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobSetupConciergeScreen(
    systemState: SystemState,
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    onRefresh: () -> Unit,
    onRunDemoTask: () -> Unit,
    mode: Mode = Mode.WIZARD
) {
    val context = LocalContext.current
    var demoPlaying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == Mode.WIZARD) "Set up Aniob" else "Setup & permissions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("setup_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("setup_refresh_button")) {
                        Text("Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Your phone, on autopilot",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tell it what you want in plain words. It taps the buttons for you. You approve anything risky.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SetupCard(
                index = 1,
                title = "See how it works",
                body = "Watch it do a task — no permissions needed yet.",
                done = false,
                actionLabel = if (demoPlaying) "Playing…" else "See how it works",
                onAction = { demoPlaying = true },
                testTag = "setup_demo"
            )
            if (demoPlaying) {
                SimulatedDemo(onDone = onRunDemoTask)
            }

            SetupCard(
                index = 2,
                title = "Accessibility access",
                body = "To tap buttons for you — only while a task runs.",
                done = systemState.a11yConnected,
                actionLabel = "Open Settings",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                testTag = "setup_a11y"
            )

            SetupCard(
                index = 3,
                title = "Overlay permission",
                body = "Shows a small status pill and approvals while you watch.",
                done = systemState.overlayGranted,
                actionLabel = "Allow overlay",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    )
                },
                testTag = "setup_overlay"
            )

            SetupCard(
                index = 4,
                title = "Notifications",
                body = "So you can pause or stop a task from anywhere.",
                done = systemState.notificationsEnabled,
                actionLabel = "Open Settings",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                testTag = "setup_notifications"
            )

            SetupCard(
                index = 5,
                title = "Battery optimisation",
                body = "Stops the system from killing Aniob mid-task.",
                done = systemState.batteryUnrestricted,
                actionLabel = "Open Settings",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                },
                testTag = "setup_battery"
            )

            SetupCard(
                index = 6,
                title = "Give Aniob a brain",
                body = "On-device (private) or cloud (fast). Either one completes this step.",
                done = systemState.modelReady,
                actionLabel = "Choose a brain",
                onAction = onNavigateToModels,
                testTag = "setup_brain"
            )

            SetupCard(
                index = 7,
                title = "Try your first task",
                body = "One tap. Watch Aniob do it.",
                done = false,
                actionLabel = "Run 'Open Settings'",
                onAction = onRunDemoTask,
                testTag = "setup_first_task"
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Wizard vs checklist presentation over identical content (UX-1 §2). */
enum class Mode { WIZARD, CHECKLIST }

@Composable
private fun SetupCard(
    index: Int,
    title: String,
    body: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(testTag)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (done) "Completed" else "Not completed",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("$index. $title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (done) {
                    Text("✓ Detected!", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!done) {
                Button(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** Scripted in-app demo captions: builds desire before any permission ask (UX-1 §1 step 2). */
@Composable
private fun SimulatedDemo(onDone: () -> Unit) {
    val captions = listOf(
        "Opening Settings…",
        "Tapping 'Display'…",
        "Done ✓"
    )
    var captionIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        captions.indices.forEach { i ->
            captionIndex = i
            kotlinx.coroutines.delay(900)
        }
        onDone()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("simulated_demo")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Simulated run", style = MaterialTheme.typography.labelSmall)
            Text(captions[captionIndex], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}
