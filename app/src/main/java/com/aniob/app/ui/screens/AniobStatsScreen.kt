package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.ui.AniobUiState
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AniobStatsScreen(uiState: AniobUiState) {
    val scores = uiState.sessionScores
    val totalSessions = scores.size
    val successCount = scores.count { it.status == "SUCCESS" }
    val successRate = if (totalSessions > 0) (successCount.toFloat() / totalSessions * 100).toInt() else 100
    val totalTokens = scores.sumOf { it.tokensUsed }
    val avgDuration = if (totalSessions > 0) scores.map { it.durationMs }.average().toInt() else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Session Stats & Room History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // KPI Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.weight(1f).testTag("kpi_success_rate")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Success Rate", style = MaterialTheme.typography.labelSmall)
                    Text("$successRate%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.weight(1f).testTag("kpi_total_tasks")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Total Tasks", style = MaterialTheme.typography.labelSmall)
                    Text("$totalSessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.weight(1f).testTag("kpi_tokens")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Total Tokens", style = MaterialTheme.typography.labelSmall)
                    Text("$totalTokens", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Persisted SessionScores (Room DB)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (scores.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No persisted sessions found. Run a task to generate Room records.",
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
                items(scores) { item ->
                    SessionScoreCard(item)
                }
            }
        }
    }
}

@Composable
fun SessionScoreCard(score: SessionScoreEntity) {
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(score.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth().testTag("session_score_${score.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = score.userPrompt,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Badge(
                    containerColor = if (score.status == "SUCCESS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                ) {
                    Text(score.status)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reason: ${score.decisionReason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Provider: ${score.providerUsed} | Steps: ${score.totalSteps}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "${score.durationMs}ms @ $dateStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
