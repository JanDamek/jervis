package com.jervis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.util.copyToClipboard
import com.jervis.ui.util.formatMessageTime
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
internal fun ChatArea(
    messages: List<ChatMessage>,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    onLoadMore: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = modifier) {
        if (messages.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Zatím žádné zprávy. Začněte konverzaci!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // "Load more" button at top
                if (hasMore) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Text("Načíst starší zprávy")
                                }
                            }
                        }
                    }
                }

                items(messages.size) { index ->
                    val message = messages[index]

                    // Check for compression boundary before this message
                    if (index > 0) {
                        val prevSequence = messages[index - 1].sequence
                        val currSequence = message.sequence
                        if (prevSequence != null && currSequence != null) {
                            val boundary = compressionBoundaries.find { b ->
                                b.afterSequence in prevSequence until currSequence
                            }
                            if (boundary != null) {
                                CompressionBoundaryIndicator(boundary)
                            }
                        }
                    }

                    ChatMessageItem(
                        message = message,
                        onEditMessage = onEditMessage,
                    )
                }
            }
        }
    }
}

/**
 * Visual indicator for context compression boundaries.
 * Shows a divider with summary of compressed messages.
 */
@Composable
private fun CompressionBoundaryIndicator(
    boundary: CompressionBoundaryDto,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        // Divider with icon and label
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Summarize,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = "Komprese kontextu (${boundary.compressedMessageCount} zpráv shrnuto)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        // Expandable summary
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = boundary.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (boundary.topics.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Témata: ${boundary.topics.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    onEditMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isMe = message.from == ChatMessage.Sender.Me

    // Error messages with red styling
    if (message.messageType == ChatMessage.MessageType.ERROR) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else if (message.messageType == ChatMessage.MessageType.PROGRESS) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // standard chat bubble - iMessage/WhatsApp style (width based on content)
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubbleWidth = maxWidth - 32.dp  // Account for LazyColumn's padding

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (isMe) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    },
            ) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (isMe) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                        ),
                    modifier = Modifier
                        .widthIn(min = 48.dp, max = maxBubbleWidth),
                ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    // Header row: sender label + action icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text =
                                if (isMe) {
                                    "Já"
                                } else {
                                    "Asistent"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Action icons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Edit button — only for user messages
                            if (isMe) {
                                IconButton(
                                    onClick = { onEditMessage(message.text) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Upravit",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            // Copy button — for both user and assistant
                            IconButton(
                                onClick = { copyToClipboard(message.text) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Kopírovat",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Message content - Markdown for Assistant, plain text for User
                    SelectionContainer {
                        if (isMe) {
                            // User messages - plain text
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            // Assistant messages - Markdown rendering
                            Markdown(
                                content = message.text,
                                colors = markdownColor(
                                    text = MaterialTheme.colorScheme.onSecondaryContainer,
                                    codeText = MaterialTheme.colorScheme.onSecondaryContainer,
                                    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                                typography = markdownTypography(
                                    text = MaterialTheme.typography.bodyMedium,
                                    code = MaterialTheme.typography.bodySmall,
                                    h1 = MaterialTheme.typography.headlineMedium,
                                    h2 = MaterialTheme.typography.headlineSmall,
                                    h3 = MaterialTheme.typography.titleLarge,
                                ),
                            )
                        }
                    }

                    // Show timestamp if available — formatted for humans
                    message.timestamp?.let { ts ->
                        if (ts.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = formatMessageTime(ts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }

                    // Show workflow steps for Assistant messages
                    if (!isMe && message.workflowSteps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        WorkflowStepsDisplay(message.workflowSteps)
                    }
                }
            }
        }
        }  // BoxWithConstraints
    }
}

@Composable
private fun WorkflowStepsDisplay(
    steps: List<ChatMessage.WorkflowStep>,
    modifier: Modifier = Modifier,
) {
    var expandedStepIndices by remember { mutableStateOf(setOf<Int>()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Použité kroky:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )

        steps.forEachIndexed { index, step ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Status icon
                    Icon(
                        when (step.status) {
                            ChatMessage.StepStatus.COMPLETED -> Icons.Default.Check
                            ChatMessage.StepStatus.FAILED -> Icons.Default.Close
                            ChatMessage.StepStatus.IN_PROGRESS -> Icons.Default.Refresh
                            ChatMessage.StepStatus.PENDING -> Icons.Default.Schedule
                        },
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = when (step.status) {
                            ChatMessage.StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            ChatMessage.StepStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    // Step label
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )

                    // Expand/collapse arrow for tools (if any)
                    if (step.tools.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                expandedStepIndices = if (index in expandedStepIndices) {
                                    expandedStepIndices - index
                                } else {
                                    expandedStepIndices + index
                                }
                            },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                if (index in expandedStepIndices) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (index in expandedStepIndices) "Skrýt nástroje" else "Zobrazit nástroje",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Tools list (collapsible)
                if (step.tools.isNotEmpty() && index in expandedStepIndices) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        step.tools.forEach { tool ->
                            Text(
                                text = "\u2022 $tool",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}
