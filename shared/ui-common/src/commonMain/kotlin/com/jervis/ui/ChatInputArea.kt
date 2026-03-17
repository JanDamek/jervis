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
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.jervis.ui.design.JTextField
import com.jervis.ui.util.PickedFile

/**
 * Unified chat input component — used in main chat and reply bubbles.
 *
 * Features:
 * - Text input with mic icon inside (trailingIcon) — hidden when typing
 * - Attach file button
 * - Send arrow button
 * - Voice recording mode: status bar + stop/cancel
 * - Shift+Enter = new line, Enter = send
 */
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
    onCancelVoice: () -> Unit = {},
    showAttach: Boolean = true,
    showCancel: Boolean = false,
    onCancel: () -> Unit = {},
    showMic: Boolean = true,
    placeholder: String = "Napište zprávu...",
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }

    Column(modifier = modifier) {
        // Attachment chips
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
                                Icon(getFileTypeIcon(file.mimeType), null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(file.filename, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        },
                        trailingIcon = { Icon(Icons.Default.Close, "Odebrat", Modifier.size(16.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isRecordingVoice) {
            // Recording mode
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                Text(
                    voiceStatus.ifBlank { "Nahrávám..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                // Stop + send
                IconButton(onClick = onMicClick, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Odeslat", tint = MaterialTheme.colorScheme.primary)
                }
                // Cancel
                IconButton(onClick = onCancelVoice, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Close, "Zrušit")
                }
            }
        } else {
            // Normal input mode
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (showAttach) {
                    JIconButton(
                        onClick = onAttachFile,
                        icon = Icons.Default.AttachFile,
                        contentDescription = "Připojit soubor",
                        enabled = enabled,
                        modifier = Modifier.size(44.dp),
                    )
                }

                JTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    label = "",
                    placeholder = placeholder,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 120.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                                if (keyEvent.isShiftPressed) {
                                    onInputChanged(inputText + "\n")
                                    true
                                } else {
                                    if (enabled && inputText.isNotBlank()) onSendClick()
                                    true
                                }
                            } else false
                        },
                    maxLines = 4,
                    singleLine = false,
                    // Mic inside text field — visible when text is empty and showMic is true
                    trailingIcon = if (showMic && inputText.isBlank()) {
                        {
                            IconButton(onClick = onMicClick, enabled = enabled) {
                                Icon(Icons.Default.Mic, "Hlasový vstup", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else null,
                )

                // Send arrow
                IconButton(
                    onClick = onSendClick,
                    enabled = enabled && (inputText.isNotBlank() || attachments.isNotEmpty()),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "Odeslat",
                        tint = if (inputText.isNotBlank() || attachments.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showCancel) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, "Zavřít")
                    }
                }
            }
        }
    }
}

private fun getFileTypeIcon(mimeType: String) = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
    mimeType.startsWith("text/") -> Icons.Default.Description
    mimeType.contains("zip") || mimeType.contains("archive") || mimeType.contains("compressed") -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}
