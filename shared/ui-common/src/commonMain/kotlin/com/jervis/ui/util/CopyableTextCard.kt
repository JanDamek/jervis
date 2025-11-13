package com.jervis.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Expect function for copying text to clipboard - platform-specific implementation
 */
@Composable
expect fun rememberClipboardManager(): ClipboardHandler

/**
 * Platform-agnostic clipboard handler interface
 */
interface ClipboardHandler {
    fun setText(text: AnnotatedString)
}

/**
 * Standard template for displaying copyable text content with copy icon in top-right corner.
 * Used across all screens for consistent UI.
 *
 * @param title Title displayed at the top of the card
 * @param content Text content to display (can be multiline)
 * @param containerColor Background color of the card
 * @param contentColor Text color
 * @param modifier Optional modifier for the card
 * @param useMonospace Whether to use monospace font for content (default: false)
 */
@Composable
fun CopyableTextCard(
    title: String,
    content: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    useMonospace: Boolean = false,
) {
    val clipboard = rememberClipboardManager()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with title and copy icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Text(
                        "📋",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Selectable content
            SelectionContainer {
                Text(
                    text = content,
                    style =
                        if (useMonospace) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    color = contentColor,
                )
            }
        }
    }
}
