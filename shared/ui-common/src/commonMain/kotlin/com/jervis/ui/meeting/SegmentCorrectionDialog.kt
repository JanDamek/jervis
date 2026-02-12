package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton

/**
 * State for segment editing dialog — carries both raw and corrected text + timing for audio playback.
 */
internal data class SegmentEditState(
    val segmentIndex: Int,
    val originalText: String,
    val editableText: String,
    val startSec: Double,
    val endSec: Double,
)

/**
 * Dialog for correcting a single transcript segment.
 * Shows original (raw) text as read-only reference with play button,
 * and an editable field pre-filled with the corrected text.
 */
@Composable
internal fun SegmentCorrectionDialog(
    originalText: String,
    editableText: String,
    isPlayingSegment: Boolean,
    onPlayToggle: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onRetranscribeSegment: (() -> Unit)? = null,
) {
    var correctedText by remember { mutableStateOf(editableText) }

    JFormDialog(
        visible = true,
        title = "Opravit segment",
        onConfirm = {
            if (correctedText.isNotBlank() && correctedText != editableText) {
                onConfirm(correctedText.trim())
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = correctedText.isNotBlank() && correctedText != editableText,
        confirmText = "Uložit",
    ) {
        // Original text (read-only) with play button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Original:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JIconButton(
                onClick = onPlayToggle,
                icon = if (isPlayingSegment) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlayingSegment) "Zastavit" else "Přehrát",
                tint = if (isPlayingSegment) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Editable corrected text
        JTextField(
            value = correctedText,
            onValueChange = { correctedText = it },
            label = "Opraveny text",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            singleLine = false,
        )
        if (onRetranscribeSegment != null) {
            Spacer(Modifier.height(8.dp))
            JTextButton(onClick = onRetranscribeSegment) {
                Text("Přepsat segment")
            }
        }
    }
}
