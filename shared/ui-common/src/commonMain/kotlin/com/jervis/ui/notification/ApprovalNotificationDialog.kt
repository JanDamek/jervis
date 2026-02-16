package com.jervis.ui.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.events.JervisEvent
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField

/**
 * In-app user task dialog — handles both approval and clarification tasks.
 *
 * **Approval mode** (event.isApproval == true):
 *   Shows interrupt action/description with Approve/Deny buttons.
 *   On deny, expands a text field for alternative instructions.
 *
 * **Clarification mode** (event.isApproval == false):
 *   Shows task title/description with a text field for the user's reply.
 *   Send button submits the reply, Dismiss closes without action.
 *
 * Works on all platforms (commonMain Compose).
 */
@Composable
fun UserTaskNotificationDialog(
    event: JervisEvent.UserTaskCreated,
    onApprove: (taskId: String) -> Unit,
    onDeny: (taskId: String, reason: String) -> Unit,
    onReply: (taskId: String, reply: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (event.isApproval) {
        ApprovalContent(event = event, onApprove = onApprove, onDeny = onDeny, onDismiss = onDismiss)
    } else {
        ClarificationContent(event = event, onReply = onReply, onDismiss = onDismiss)
    }
}

/**
 * Legacy wrapper — kept for backward compatibility.
 */
@Composable
fun ApprovalNotificationDialog(
    event: JervisEvent.UserTaskCreated,
    onApprove: (taskId: String) -> Unit,
    onDeny: (taskId: String, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    UserTaskNotificationDialog(
        event = event,
        onApprove = onApprove,
        onDeny = onDeny,
        onReply = { _, _ -> },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ApprovalContent(
    event: JervisEvent.UserTaskCreated,
    onApprove: (taskId: String) -> Unit,
    onDeny: (taskId: String, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showDenyInput by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Schválení: ${event.interruptAction ?: "akce"}",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = event.interruptDescription ?: event.title,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (showDenyInput) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Důvod zamítnutí / alternativní instrukce:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    JTextField(
                        value = denyReason,
                        onValueChange = { denyReason = it },
                        label = "Důvod",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        singleLine = false,
                        placeholder = "Napište, jak má agent pokračovat...",
                    )
                }
            }
        },
        confirmButton = {
            if (showDenyInput) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    JSecondaryButton(onClick = {
                        showDenyInput = false
                        denyReason = ""
                    }) {
                        Text("Zpět")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    JDestructiveButton(
                        onClick = {
                            onDeny(event.taskId, denyReason)
                        },
                        enabled = denyReason.isNotBlank(),
                    ) {
                        Text("Zamítnout")
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    JSecondaryButton(
                        onClick = { showDenyInput = true },
                    ) {
                        Text("Zamítnout", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    JPrimaryButton(onClick = {
                        onApprove(event.taskId)
                    }) {
                        Text("Povolit")
                    }
                }
            }
        },
        dismissButton = null,
    )
}

@Composable
private fun ClarificationContent(
    event: JervisEvent.UserTaskCreated,
    onReply: (taskId: String, reply: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var replyText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Úloha vyžaduje odpověď",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    text = event.interruptDescription ?: event.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                JTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    label = "Vaše odpověď",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    singleLine = false,
                    placeholder = "Napište odpověď pro agenta...",
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                JSecondaryButton(onClick = onDismiss) {
                    Text("Zavřít")
                }
                Spacer(modifier = Modifier.width(8.dp))
                JPrimaryButton(
                    onClick = { onReply(event.taskId, replyText) },
                    enabled = replyText.isNotBlank(),
                ) {
                    Text("Odeslat")
                }
            }
        },
        dismissButton = null,
    )
}
