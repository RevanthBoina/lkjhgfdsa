package com.aniob.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniob.app.ui.screens.AniobChatScreen
import com.aniob.app.ui.screens.AniobSettingsScreen
import com.aniob.app.ui.screens.AniobStatsScreen
import com.aniob.app.ui.screens.AniobTrackerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobMainScreen(viewModel: AniobViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aniob - UI Automation Agent") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (uiState.isRunning) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("running_badge")
                        ) {
                            Text("RUNNING")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_bottom_nav")) {
                NavigationBarItem(
                    selected = uiState.currentTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                    label = { Text("Chat") },
                    modifier = Modifier.testTag("nav_chat")
                )
                NavigationBarItem(
                    selected = uiState.currentTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = "Tracker") },
                    label = { Text("Tracker") },
                    modifier = Modifier.testTag("nav_tracker")
                )
                NavigationBarItem(
                    selected = uiState.currentTab == 2,
                    onClick = { viewModel.setTab(2) },
                    icon = { Icon(Icons.Default.Insights, contentDescription = "Stats") },
                    label = { Text("Stats") },
                    modifier = Modifier.testTag("nav_stats")
                )
                NavigationBarItem(
                    selected = uiState.currentTab == 3,
                    onClick = { viewModel.setTab(3) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (uiState.currentTab) {
                0 -> AniobChatScreen(
                    uiState = uiState,
                    onSubmitTask = { viewModel.submitTask(it) },
                    onStopTask = { viewModel.stopCurrentTask() },
                    onShowTrackerSheet = { viewModel.setShowTrackerSheet(it) },
                    onFilterChanged = { viewModel.setTrackerFilter(it) }
                )
                1 -> AniobTrackerScreen(
                    uiState = uiState,
                    onFilterChanged = { viewModel.setTrackerFilter(it) }
                )
                2 -> AniobStatsScreen(uiState = uiState)
                3 -> AniobSettingsScreen(
                    uiState = uiState,
                    onSaveSettings = { key, model -> viewModel.updateSettings(key, model) }
                )
            }
        }
    }

    // Modal Grill-Me Clarification Sheet
    if (uiState.showGrillMeSheet && uiState.grillMeResult != null) {
        AniobGrillMeSheet(
            grillResult = uiState.grillMeResult!!,
            onConfirm = { answers -> viewModel.onGrillAnswersSubmitted(answers) },
            onDismiss = { viewModel.dismissGrillMe() }
        )
    }
}
