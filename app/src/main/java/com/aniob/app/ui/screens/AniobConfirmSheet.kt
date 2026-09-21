package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest

/**
 * Seen Confirmation sheet (UX-4 §1).
 *
 * Both the foreground dialog and the minimized full-screen intent land here, so the user always
 * sees What / Why / Risk before a risky step. The decision completes the suspending gate in the
 * ViewModel — nothing dispatches until this returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    request: ConfirmRequest,
    onDecision: (ConfirmDecision) -> Unit
) {
    var detailsExpanded by remember { mutableStateOf(false) }

    val riskColor = when (request.risk) {
        ConfirmRequest.Risk.HIGH -> MaterialTheme.colorScheme.error
        ConfirmRequest.Risk.MEDIUM -> MaterialTheme.colorScheme.tertiary
        ConfirmRequest.Risk.LOW -> MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = { /* blocking: a decision is required or the 60s timeout denies */ },
        modifier = Modifier.testTag("confirm_sheet"),
        icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = riskColor) },
        title = { Text(request.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = riskColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(
                        text = "${request.risk.name} RISK",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = riskColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text("What: ${request.what}", style = MaterialTheme.typography.bodyMedium)
                Text("Why: ${request.why}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "If you don't answer in ${request.timeoutSec}s, Aniob will deny this step.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.testTag("confirm_details_toggle")
                ) {
                    Text(if (detailsExpanded) "Hide details" else "Details")
                }
                if (detailsExpanded) {
                    Text(request.details, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { onDecision(ConfirmDecision.APPROVE_ONCE) },
                    modifier = Modifier.testTag("confirm_approve_once")
                ) { Text("Approve once") }
                TextButton(
                    onClick = { onDecision(ConfirmDecision.APPROVE_FOR_TASK) },
                    modifier = Modifier.testTag("confirm_approve_task")
                ) { Text("Approve for this task") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDecision(ConfirmDecision.DENY) },
                modifier = Modifier.testTag("confirm_deny")
            ) { Text("Deny") }
        }
    )
}
