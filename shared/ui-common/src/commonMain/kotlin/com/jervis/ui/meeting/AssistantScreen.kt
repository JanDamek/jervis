package com.jervis.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.ui.design.JervisSpacing

/**
 * Full-screen Assistant view during an active meeting.
 *
 * Top half: rolling live transcript (TRANSCRIPT messages concatenated with
 * soft wrapping, auto-scroll to bottom).
 * Bottom half: hints (SUGGESTION / QUESTION_PREDICT / STATUS / etc.) —
 * discrete colored cards, auto-scroll.
 *
 * No setup required on this device: messages arrive via RPC event stream the
 * moment a helper session is active on the server (started from iOS or any
 * other recording device).
 */
@Composable
fun AssistantScreen(
    messages: List<HelperMessageDto>,
    isConnected: Boolean,
    ttsEnabled: Boolean = false,
    onToggleTts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val transcript = messages.filter { it.type == HelperMessageType.TRANSCRIPT }
    val hints = messages.filter { it.type != HelperMessageType.TRANSCRIPT }

    val transcriptState = rememberLazyListState()
    val hintsState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) transcriptState.animateScrollToItem(transcript.size - 1)
    }
    LaunchedEffect(hints.size) {
        if (hints.isNotEmpty()) hintsState.animateScrollToItem(hints.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(JervisSpacing.outerPadding),
    ) {
        // Status bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isConnected) "Asistent aktivní" else "Asistent čeká",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "${transcript.size} segmentů · ${hints.size} hintů",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // TTS toggle — device speaks hints aloud via xTTS v2 (user's voice)
            Text(
                text = "TTS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Switch(checked = ttsEnabled, onCheckedChange = { onToggleTts() })
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Transcript (top half, scrollable)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Přepis",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (transcript.isEmpty()) {
                    Text(
                        text = "Zatím žádná řeč. Čekání na první segment z mikrofonu...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = transcriptState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(transcript) { msg ->
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Hints (bottom half)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Hinty asistenta",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (hints.isEmpty()) {
                    Text(
                        text = "Zatím žádné hinty. Claude pošle návrh, když bude co užitečného říct.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = hintsState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(hints) { msg ->
                            HintBubble(msg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintBubble(msg: HelperMessageDto) {
    val (bg, label) = when (msg.type) {
        HelperMessageType.SUGGESTION -> Color(0xFFE6F7E6) to "Návrh"
        HelperMessageType.QUESTION_PREDICT -> Color(0xFFFFF4E0) to "Možná otázka"
        HelperMessageType.STATUS -> Color(0xFFEDEDED) to "Stav"
        HelperMessageType.TRANSLATION -> Color(0xFFE3F2FD) to "Překlad"
        HelperMessageType.VISUAL_INSIGHT -> Color(0xFFF3E5F5) to "Scéna"
        HelperMessageType.WHITEBOARD_OCR -> Color(0xFFF5F5F0) to "Tabule"
        HelperMessageType.SCREEN_OCR -> Color(0xFFF5F0FF) to "Obrazovka"
        HelperMessageType.TRANSCRIPT -> Color.Transparent to ""
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray,
            )
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
