package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing

/**
 * Agent chat panel with conversation history and instruction input.
 */
@Composable
internal fun AgentChatPanel(
    chatHistory: List<CorrectionChatMessageDto>,
    pendingMessage: CorrectionChatMessageDto?,
    isCorrecting: Boolean,
    onSendInstruction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var instructionText by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    // Build display messages: persisted + pending optimistic
    val displayMessages = remember(chatHistory, pendingMessage) {
        val messages = chatHistory.toMutableList()
        // Add pending message only if it's not already persisted
        if (pendingMessage != null && messages.none { it.role == com.jervis.dto.meeting.CorrectionChatRole.USER && it.text == pendingMessage.text && it.timestamp == pendingMessage.timestamp }) {
            messages.add(pendingMessage)
        }
        messages
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(displayMessages.size) {
        if (displayMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(displayMessages.size - 1)
        }
    }

    Column(modifier = modifier) {
        HorizontalDivider()

        // Chat history
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = JervisSpacing.outerPadding),
            state = chatListState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (displayMessages.isEmpty()) {
                item {
                    Text(
                        text = "Zadejte instrukci pro opravu přepisu...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(displayMessages.size) { index ->
                    ChatMessageBubble(message = displayMessages[index])
                }
            }

            // Processing indicator
            if (isCorrecting) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Agent opravuje přepis...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = JervisSpacing.outerPadding)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            JTextField(
                value = instructionText,
                onValueChange = { instructionText = it },
                label = "Instrukce",
                placeholder = "Instrukce pro opravu...",
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 3,
                singleLine = false,
                enabled = !isCorrecting,
            )
            JTextButton(
                onClick = {
                    if (instructionText.isNotBlank()) {
                        onSendInstruction(instructionText)
                        instructionText = ""
                    }
                },
                enabled = instructionText.isNotBlank() && !isCorrecting,
            ) {
                Text("Odeslat")
            }
        }
    }
}

/**
 * Chat message bubble. User messages align right, agent messages align left.
 */
@Composable
private fun ChatMessageBubble(message: CorrectionChatMessageDto) {
    val isUser = message.role == com.jervis.dto.meeting.CorrectionChatRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (message.status == "error") {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else if (message.status == "error") {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                if (!isUser && message.rulesCreated > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pravidel vytvořeno: ${message.rulesCreated}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
        // Timestamp
        Text(
            text = formatChatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
