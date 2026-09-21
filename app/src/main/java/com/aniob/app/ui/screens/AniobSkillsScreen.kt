package com.aniob.app.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.AniobUiState
import com.aniob.core.skills.AniobSkill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobSkillsScreen(
    uiState: AniobUiState,
    skills: List<AniobSkill>,
    onBack: () -> Unit,
    onTeach: () -> Unit,
    onReplay: (String) -> Unit,
    onToggleSkill: (name: String, enabled: Boolean) -> Unit,
    onDeleteSkill: (name: String) -> Unit,
    vaultEntries: () -> Map<String, String>,
    onForgetVault: (String) -> Unit
) {
    val builtInNames = setOf("open_settings", "send_quick_message")
    val active = skills.filter { it.name !in builtInNames }
    val builtIn = skills.filter { it.name in builtInNames }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("skills_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("No skills yet", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onTeach, modifier = Modifier.testTag("skills_go_chat")) {
                        Text("Go to chat")
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (active.isNotEmpty()) {
                item { SectionHeader("Active") }
                items(active, key = { it.name }) { skill ->
                    SkillRow(
                        skill = skill,
                        enabled = skill.name !in uiState.disabledSkills,
                        onReplay = { onReplay(skill.triggerKeywords.firstOrNull() ?: skill.name) },
                        onToggle = { enabled -> onToggleSkill(skill.name, enabled) }
                    )
                }
            }

            if (builtIn.isNotEmpty()) {
                item { SectionHeader("Built-in") }
                items(builtIn, key = { "builtin_${it.name}" }) { skill ->
                    SkillRow(
                        skill = skill,
                        enabled = skill.name !in uiState.disabledSkills,
                        onReplay = { onReplay(skill.triggerKeywords.firstOrNull() ?: skill.name) },
                        onToggle = { enabled -> onToggleSkill(skill.name, enabled) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SkillRow(
    skill: AniobSkill,
    enabled: Boolean,
    onReplay: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("skill_row_${skill.name}")) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    skill.triggerKeywords.firstOrNull() ?: skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${skill.steps.size} steps · ${skill.riskTier}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onReplay,
                modifier = Modifier.testTag("skill_replay_${skill.name}")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Replay")
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("skill_toggle_${skill.name}")
            )
        }
    }
}
