package com.jervis.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

    // Internal TextFieldValue to track cursor position
    var textFieldValue by remember { mutableStateOf(TextFieldValue(inputText)) }

    // Sync from external String → internal TextFieldValue (when parent changes text, e.g. clear after send)
    LaunchedEffect(inputText) {
        if (textFieldValue.text != inputText) {
            textFieldValue = TextFieldValue(inputText, TextRange(inputText.length))
        }
    }

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
            // Voice session mode — shows pipeline state with animated indicator
            val isActive = voiceStatus.contains("Poslouchám") || voiceStatus.contains("Mluvíte") || voiceStatus.contains("Nahrávám")
            val isProcessing = voiceStatus.contains("Přepisuji") || voiceStatus.contains("Zpracovávám") || voiceStatus.contains("Generuji")
            val statusColor = when {
                voiceStatus.contains("Mluvíte") -> MaterialTheme.colorScheme.error
                isProcessing -> MaterialTheme.colorScheme.tertiary
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusIcon = when {
                voiceStatus.contains("Mluvíte") -> Icons.Default.Mic
                voiceStatus.contains("Připojuji") -> Icons.Default.Mic
                isProcessing -> Icons.Default.Mic
                isActive -> Icons.Default.Mic
                else -> Icons.Default.Mic
            }

            // Pulsating dot animation for active states
            val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            )

            Column(
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Animated status icon
                    Icon(
                        statusIcon, null,
                        tint = statusColor.copy(alpha = if (isActive || isProcessing) pulseAlpha else 1f),
                        modifier = Modifier.size(24.dp),
                    )

                    // Status text
                    Text(
                        voiceStatus.ifBlank { "Připojuji..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        modifier = Modifier.weight(1f),
                    )

                    // Stop button
                    IconButton(onClick = onMicClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Stop, "Zastavit", tint = MaterialTheme.colorScheme.error)
                    }
                    // Cancel
                    IconButton(onClick = onCancelVoice, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, "Zrušit")
                    }
                }

                // Audio level bars when actively listening/recording
                if (isActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(16.dp),
                    ) {
                        repeat(12) { i ->
                            val barAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.2f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(400 + i * 50, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "bar_$i",
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height((4 + (barAlpha * 12)).dp)
                                    .background(
                                        statusColor.copy(alpha = barAlpha * 0.7f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "● LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor.copy(alpha = pulseAlpha),
                        )
                    }
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
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onInputChanged(newValue.text)
                    },
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
                                    val cursorPos = textFieldValue.selection.start
                                    val newText = textFieldValue.text.substring(0, cursorPos) + "\n" + textFieldValue.text.substring(cursorPos)
                                    val newCursor = cursorPos + 1
                                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                                    onInputChanged(newText)
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
