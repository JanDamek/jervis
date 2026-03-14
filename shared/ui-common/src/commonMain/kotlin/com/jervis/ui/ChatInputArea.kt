package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.util.PickedFile

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InputArea(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean,
    queueSize: Int = 0,
    attachments: List<PickedFile> = emptyList(),
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    requestFocus: Boolean = false,
    isRecordingVoice: Boolean = false,
    voiceStatus: String = "",
    onMicClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier) {
        // Attachment chips row
        if (attachments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                attachments.forEachIndexed { index, file ->
                    AssistChip(
                        onClick = { onRemoveAttachment(index) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    getFileTypeIcon(file.mimeType),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = file.filename,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Odebrat",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Voice recording status bar
        if (isRecordingVoice) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = voiceStatus.ifBlank { "Nahravam..." },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Input row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Attach file button
            JIconButton(
                onClick = onAttachFile,
                icon = Icons.Default.AttachFile,
                contentDescription = "Pripojit soubor",
                enabled = enabled && !isRecordingVoice,
                modifier = Modifier.size(44.dp),
            )

            JTextField(
                value = if (isRecordingVoice) "" else inputText,
                onValueChange = onInputChanged,
                label = "",
                placeholder = if (isRecordingVoice) "Nahravam hlas..." else "Napiste zpravu...",
                enabled = enabled && !isRecordingVoice,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 120.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                                if (keyEvent.isShiftPressed) {
                                    onInputChanged(inputText + "\n")
                                    true
                                } else {
                                    if (enabled && inputText.isNotBlank()) {
                                        onSendClick()
                                    }
                                    true
                                }
                            } else {
                                false
                            }
                        },
                maxLines = 4,
                singleLine = false,
                // Mic icon inside text field — visible when not typing, becomes stop when recording
                trailingIcon = {
                    if (inputText.isBlank() || isRecordingVoice) {
                        IconButton(
                            onClick = onMicClick,
                            enabled = enabled,
                        ) {
                            Icon(
                                if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isRecordingVoice) "Zastavit" else "Hlasovy vstup",
                                tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )

            JPrimaryButton(
                onClick = if (isRecordingVoice) onMicClick else onSendClick,
                enabled = enabled && (inputText.isNotBlank() || attachments.isNotEmpty() || isRecordingVoice),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (isRecordingVoice) "Stop" else "Odeslat")
            }
        }
    }
}

/**
 * Get an appropriate icon for a file based on its MIME type.
 */
private fun getFileTypeIcon(mimeType: String) = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
    mimeType.startsWith("text/") -> Icons.Default.Description
    mimeType.contains("zip") || mimeType.contains("archive") || mimeType.contains("compressed") -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}
