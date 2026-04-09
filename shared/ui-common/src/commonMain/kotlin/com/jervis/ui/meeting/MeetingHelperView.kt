package com.jervis.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.ui.design.JervisSpacing

/**
 * Meeting Helper View — displays real-time translation + suggestions on the device.
 *
 * Large readable text, auto-scroll, color-coded by type:
 * - Translation (blue) — translated text from source language
 * - Suggestion (green) — suggested responses
 * - Question predict (amber) — anticipated questions
 * - Status (gray) — connection/session status
 */
@Composable
fun MeetingHelperView(
    messages: List<HelperMessageDto>,
    meetingTitle: String?,
    isConnected: Boolean,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(JervisSpacing.outerPadding),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Asistence",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (meetingTitle != null) {
                    Text(
                        text = meetingTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Connection status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)),
                )
                Text(
                    text = if (isConnected) "Aktivní" else "Odpojeno",
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(JervisSpacing.touchTarget),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Ukončit asistenci")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Messages list
        if (messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Čekám na přepis...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Překlad a návrhy se zobrazí po zahájení řeči",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(messages, key = { "${it.type}_${it.timestamp}_${it.text.hashCode()}" }) { message ->
                    HelperMessageCard(message)
                }
            }
        }
    }
}

@Composable
private fun HelperMessageCard(message: HelperMessageDto) {
    val clipboard = LocalClipboardManager.current

    val (containerColor, labelText) = when (message.type) {
        HelperMessageType.TRANSLATION -> Pair(
            Color(0xFF1565C0).copy(alpha = 0.12f),
            "Překlad",
        )
        HelperMessageType.SUGGESTION -> Pair(
            Color(0xFF2E7D32).copy(alpha = 0.12f),
            "Návrh odpovědi",
        )
        HelperMessageType.QUESTION_PREDICT -> Pair(
            Color(0xFFFF8F00).copy(alpha = 0.12f),
            "Očekávaná otázka",
        )
        HelperMessageType.STATUS -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            "Stav",
        )
        HelperMessageType.VISUAL_INSIGHT -> Pair(
            Color(0xFF7B1FA2).copy(alpha = 0.12f),
            "Vizuální kontext",
        )
        HelperMessageType.WHITEBOARD_OCR -> Pair(
            Color(0xFFE65100).copy(alpha = 0.12f),
            "Tabule",
        )
        HelperMessageType.SCREEN_OCR -> Pair(
            Color(0xFF0277BD).copy(alpha = 0.12f),
            "Obrazovka",
        )
    }

    val labelColor = when (message.type) {
        HelperMessageType.TRANSLATION -> Color(0xFF1565C0)
        HelperMessageType.SUGGESTION -> Color(0xFF2E7D32)
        HelperMessageType.QUESTION_PREDICT -> Color(0xFFFF8F00)
        HelperMessageType.STATUS -> MaterialTheme.colorScheme.onSurfaceVariant
        HelperMessageType.VISUAL_INSIGHT -> Color(0xFF7B1FA2)
        HelperMessageType.WHITEBOARD_OCR -> Color(0xFFE65100)
        HelperMessageType.SCREEN_OCR -> Color(0xFF0277BD)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.timestamp.isNotBlank()) {
                        val timeDisplay = message.timestamp.let { ts ->
                            if (ts.contains("T")) ts.substringAfter("T").take(8) else ts.take(8)
                        }
                        Text(
                            text = timeDisplay,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (message.type != HelperMessageType.STATUS) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(message.text)) },
                            modifier = Modifier.size(JervisSpacing.touchTarget),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Kopírovat",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main text — large and readable
            Text(
                text = message.text,
                style = if (message.type == HelperMessageType.TRANSLATION) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Context (if present)
            if (message.context.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Language info for translations
            if (message.type == HelperMessageType.TRANSLATION && message.fromLang.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${message.fromLang.uppercase()} → ${message.toLang.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}
