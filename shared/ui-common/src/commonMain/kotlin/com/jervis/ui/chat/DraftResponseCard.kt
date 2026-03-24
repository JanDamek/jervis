package com.jervis.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.design.LocalJervisSemanticColors

/**
 * Card for displaying a draft auto-response in chat.
 *
 * Shows the original message summary, draft text, confidence indicator.
 * Three action buttons: [Odeslat] [Upravit] [Ignorovat].
 * [Upravit] opens inline editor (TextField pre-filled with draft).
 * After action -> calls the appropriate feedback handler.
 */
@Composable
fun DraftResponseCard(
    originalMessageSummary: String,
    draftText: String,
    confidence: Float,
    onSend: (String) -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(draftText) { mutableStateOf(draftText) }

    JCard(modifier = modifier) {
        // Header: auto-response icon + label
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Návrh odpovědi",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            ConfidenceIndicator(confidence = confidence)
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Original message summary
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = originalMessageSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Draft text or editor
        AnimatedVisibility(visible = !isEditing) {
            Text(
                text = draftText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AnimatedVisibility(visible = isEditing) {
            JTextField(
                value = editedText,
                onValueChange = { editedText = it },
                label = "Upravit odpověď",
                singleLine = false,
                minLines = 3,
                maxLines = 10,
            )
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            JPrimaryButton(
                onClick = {
                    onSend(if (isEditing) editedText else draftText)
                },
                enabled = if (isEditing) editedText.isNotBlank() else true,
            ) {
                Text("Odeslat")
            }
            JSecondaryButton(
                onClick = {
                    isEditing = !isEditing
                    if (isEditing) editedText = draftText
                },
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isEditing) "Zrušit úpravu" else "Upravit")
            }
            JSecondaryButton(onClick = onIgnore) {
                Text("Ignorovat")
            }
        }
    }
}

/**
 * Visual confidence indicator: colored bar + percentage label.
 */
@Composable
private fun ConfidenceIndicator(
    confidence: Float,
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalJervisSemanticColors.current
    val color = when {
        confidence >= 0.8f -> semanticColors.success
        confidence >= 0.5f -> semanticColors.warning
        else -> MaterialTheme.colorScheme.error
    }
    val label = "${(confidence * 100).toInt()}%"

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LinearProgressIndicator(
            progress = { confidence },
            modifier = Modifier.width(48.dp).height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
