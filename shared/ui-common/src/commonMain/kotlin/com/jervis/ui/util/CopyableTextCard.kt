package com.jervis.ui.util

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Card displaying selectable text content with title.
 * Text is natively selectable (long-press on mobile, click+drag on desktop).
 * Uses outlined card style per design system.
 *
 * @param title Title displayed at the top of the card
 * @param content Text content to display (can be multiline)
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
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Selectable content — no copy button, native text selection only
            SelectionContainer {
                Text(
                    text = content,
                    style = if (useMonospace) {
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
