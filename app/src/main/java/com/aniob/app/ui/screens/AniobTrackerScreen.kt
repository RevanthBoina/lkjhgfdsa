package com.aniob.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.AniobUiState
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobStepRecord

@Composable
fun AniobTrackerScreen(
    uiState: AniobUiState,
    onFilterChanged: (String) -> Unit
) {
    val filteredSteps = when (uiState.trackerFilter) {
        "CORRECT" -> uiState.steps.filter { it.verifiedSuccess }
        "FAILED" -> uiState.steps.filter { !it.verifiedSuccess }
        else -> uiState.steps
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Execution Tracker",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Filter Tabs: All, Correct, Failed (Zero LLM calls required)
        TabRow(
            selectedTabIndex = when (uiState.trackerFilter) {
                "CORRECT" -> 1
                "FAILED" -> 2
                else -> 0
            },
            modifier = Modifier.testTag("tracker_filter_tabs")
        ) {
            Tab(
                selected = uiState.trackerFilter == "ALL",
                onClick = { onFilterChanged("ALL") },
                text = { Text("All (${uiState.steps.size})") },
                modifier = Modifier.testTag("filter_all")
            )
            Tab(
                selected = uiState.trackerFilter == "CORRECT",
                onClick = { onFilterChanged("CORRECT") },
                text = { Text("Correct (${uiState.steps.count { it.verifiedSuccess }})") },
                modifier = Modifier.testTag("filter_correct")
            )
            Tab(
                selected = uiState.trackerFilter == "FAILED",
                onClick = { onFilterChanged("FAILED") },
                text = { Text("Failed (${uiState.steps.count { !it.verifiedSuccess }})") },
                modifier = Modifier.testTag("filter_failed")
            )
        }

        if (filteredSteps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.steps.isEmpty()) "No steps recorded yet. Execute a task to monitor progress." else "No steps matching filter '${uiState.trackerFilter}'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSteps) { step ->
                    StepRecordCard(step)
                }
            }
        }
    }
}

@Composable
fun StepRecordCard(step: AniobStepRecord) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (step.verifiedSuccess) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().testTag("step_card_${step.stepIndex}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = if (step.verifiedSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (step.verifiedSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Step ${step.stepIndex}: ${step.action.thought}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Badge {
                    Text(step.provider)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Action: ${formatActionSummary(step.action)}",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!step.failureReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Issue: ${step.failureReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Latency: ${step.latencyMs}ms | Tokens: ${step.tokensUsed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Hash: ${step.screenHash.take(6)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatActionSummary(action: AniobAction): String = when (action) {
    is AniobAction.Click -> "CLICK(node=${action.targetNodeId})"
    is AniobAction.InputText -> "TYPE(\"${action.text}\" -> node=${action.targetNodeId})"
    is AniobAction.Swipe -> "SWIPE(${action.direction})"
    is AniobAction.PressKey -> "KEY(${action.key})"
    is AniobAction.Wait -> "WAIT(${action.durationMs}ms)"
    is AniobAction.Finish -> "FINISH: ${action.summary}"
    is AniobAction.Fail -> "FAIL: ${action.reason}"
}
