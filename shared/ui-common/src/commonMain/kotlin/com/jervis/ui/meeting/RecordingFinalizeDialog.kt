package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.ui.design.JervisSpacing

/**
 * Dialog shown after stopping a recording.
 * Allows the user to classify the meeting type and optionally set a title.
 */
@Composable
fun RecordingFinalizeDialog(
    durationSeconds: Long,
    onFinalize: (title: String?, meetingType: MeetingTypeEnum) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(MeetingTypeEnum.MEETING) }

    val meetingTypeLabels = mapOf(
        MeetingTypeEnum.MEETING to "Schuzka",
        MeetingTypeEnum.TASK_DISCUSSION to "Diskuse ukolu",
        MeetingTypeEnum.STANDUP_PROJECT to "Standup projekt",
        MeetingTypeEnum.STANDUP_TEAM to "Standup tym",
        MeetingTypeEnum.INTERVIEW to "Pohovor",
        MeetingTypeEnum.WORKSHOP to "Workshop",
        MeetingTypeEnum.REVIEW to "Review",
        MeetingTypeEnum.OTHER to "Jine",
    )

    AlertDialog(
        onDismissRequest = { /* Don't dismiss by clicking outside */ },
        title = { Text("Dokoncit nahravku") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Duration display
                Text(
                    text = "Delka: ${formatDuration(durationSeconds)}",
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Meeting type selector
                Text(
                    text = "Typ meetingu:",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    MeetingTypeEnum.entries.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(JervisSpacing.touchTarget)
                                .selectable(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedType == type,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                meetingTypeLabels[type] ?: type.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nazev (volitelne)") },
                    placeholder = { Text("Nazev nahravky...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onFinalize(title.ifBlank { null }, selectedType)
                },
            ) {
                Text("Dokoncit")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Zrusit")
            }
        },
    )
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
