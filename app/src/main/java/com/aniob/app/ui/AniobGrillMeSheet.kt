package com.aniob.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.core.grillme.AniobGrillMeResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobGrillMeSheet(
    grillResult: AniobGrillMeResult,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Map question ID to user answer
    val userAnswers = remember {
        mutableStateMapOf<String, String>().apply {
            grillResult.questions.forEach { q ->
                put(q.id, q.defaultOption ?: q.options.firstOrNull().orEmpty())
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("grill_me_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Clarification Needed (Grill-Me)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Aniob detected ambiguity in your request. Please select options or enter details to proceed:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            grillResult.questions.forEach { question ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = question.question,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Options chips / row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        question.options.forEach { option ->
                            val selected = userAnswers[question.id] == option
                            FilterChip(
                                selected = selected,
                                onClick = { userAnswers[question.id] = option },
                                label = { Text(option) },
                                modifier = Modifier.testTag("chip_${question.id}_$option")
                            )
                        }
                    }

                    // Free text input if enabled
                    if (question.allowFreeText) {
                        OutlinedTextField(
                            value = userAnswers[question.id] ?: "",
                            onValueChange = { userAnswers[question.id] = it },
                            label = { Text("Or specify custom answer") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("freetext_${question.id}"),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("grill_me_cancel")
                ) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(userAnswers.toMap()) },
                    modifier = Modifier.testTag("grill_me_confirm")
                ) {
                    Text("Confirm & Proceed")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
