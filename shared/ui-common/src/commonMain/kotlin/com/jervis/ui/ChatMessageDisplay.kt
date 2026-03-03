package com.jervis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.chat.ChatDisplayItem
import com.jervis.ui.queue.OrchestratorProgressInfo
import com.jervis.ui.util.copyToClipboard
import com.jervis.ui.util.formatMessageTime
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
internal fun ChatArea(
    displayItems: List<ChatDisplayItem>,
    expandedThreads: Set<String>,
    onToggleThread: (String) -> Unit,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    orchestratorProgress: OrchestratorProgressInfo? = null,
    onLoadMore: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onSendThreadReply: (taskId: String, text: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // reverseLayout=true: item 0 is at the bottom of the screen.
    val reversedItems = remember(displayItems) { displayItems.asReversed() }
    Box(modifier = modifier) {
        if (displayItems.isEmpty()) {
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
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(reversedItems.size) { index ->
                    val item = reversedItems[index]
                    when (item) {
                        is ChatDisplayItem.Standalone -> ChatMessageItem(
                            message = item.message,
                            orchestratorProgress = if (item.message.messageType == ChatMessage.MessageType.PROGRESS) orchestratorProgress else null,
                            onEditMessage = onEditMessage,
                        )
                        is ChatDisplayItem.Thread -> ThreadCard(
                            thread = item,
                            isExpanded = item.taskId in expandedThreads,
                            onToggle = { onToggleThread(item.taskId) },
                            onSendReply = { text -> onSendThreadReply(item.taskId, text) },
                            onEditMessage = onEditMessage,
                        )
                    }
                }

                // "Load more" button at top (= end of reversed list)
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
            }
        }
    }
}

// ── Thread Card ──────────────────────────────────────────────────────────

/**
 * Background task thread card — contains task result header + user/assistant replies.
 * Collapsed: shows summary + unread badge. Expanded: full result + inline replies.
 */
@Composable
private fun ThreadCard(
    thread: ChatDisplayItem.Thread,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSendReply: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isReplying by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    val header = thread.header
    val isSuccess = header.metadata["success"] != "false"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: icon + title + unread badge + expand arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = header.metadata["taskTitle"] ?: "Background úloha",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Unread badge when collapsed
                if (!isExpanded && thread.unreadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(thread.unreadCount.toString())
                    }
                }
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Skrýt" else "Zobrazit",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Collapsed: first line preview
            AnimatedVisibility(visible = !isExpanded) {
                Text(
                    text = header.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Expanded: full result + replies
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    // Full markdown result
                    SelectionContainer {
                        Markdown(
                            content = header.text,
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onSurfaceVariant,
                                codeBackground = MaterialTheme.colorScheme.surface,
                            ),
                            typography = markdownTypography(
                                text = MaterialTheme.typography.bodySmall,
                                code = MaterialTheme.typography.bodySmall,
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // Replies section
                    if (thread.replies.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            thread.replies.forEach { reply ->
                                ThreadReplyItem(
                                    message = reply,
                                    onEditMessage = onEditMessage,
                                )
                            }
                        }
                    }
                }
            }

            // Footer: timestamp + reply toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Show last activity timestamp
                val displayTimestamp = thread.replies.lastOrNull()?.timestamp ?: header.timestamp
                displayTimestamp?.let { ts ->
                    if (ts.isNotBlank()) {
                        Text(
                            text = formatMessageTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                if (!isReplying) {
                    TextButton(
                        onClick = { isReplying = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Reagovat",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Inline reply input (shown when "Reagovat" clicked)
            AnimatedVisibility(visible = isReplying) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            isReplying = false
                            replyText = ""
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Zrušit",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        placeholder = {
                            Text(
                                "Napsat odpověď...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    IconButton(
                        onClick = {
                            if (replyText.isNotBlank()) {
                                onSendReply(replyText)
                                replyText = ""
                                isReplying = false
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Odeslat",
                            modifier = Modifier.size(18.dp),
                            tint = if (replyText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact reply item inside a thread card — smaller than standalone chat bubbles.
 */
@Composable
private fun ThreadReplyItem(
    message: ChatMessage,
    onEditMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isMe = message.from == ChatMessage.Sender.Me
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isMe) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Sender label
                Text(
                    text = if (isMe) "Já" else "Asistent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.height(2.dp))

                // Content
                SelectionContainer {
                    if (isMe) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val stableContent = remember(message.text) {
                            message.text
                                .replace("\r\n", "\n")
                                .replace("\r", "\n")
                                .replace("\u0000", "")
                        }
                        Markdown(
                            content = stableContent,
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onSurface,
                                codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            typography = markdownTypography(
                                text = MaterialTheme.typography.bodySmall,
                                code = MaterialTheme.typography.bodySmall,
                            ),
                        )
                    }
                }

                // Timestamp
                message.timestamp?.let { ts ->
                    if (ts.isNotBlank()) {
                        Text(
                            text = formatMessageTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Compression Boundary ─────────────────────────────────────────────────

/**
 * Visual indicator for context compression boundaries.
 * Shows a divider with summary of compressed messages.
 */
@Composable
private fun CompressionBoundaryIndicator(
    boundary: com.jervis.dto.CompressionBoundaryDto,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
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
                modifier =
                    Modifier
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

// ── Standalone Message Item ──────────────────────────────────────────────

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    orchestratorProgress: OrchestratorProgressInfo? = null,
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
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Main progress row: spinner + text
            Row(
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

            // Inline orchestrator progress details
            if (orchestratorProgress != null) {
                Row(
                    modifier = Modifier.padding(start = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Node label
                    val nodeLabel = nodeLabels[orchestratorProgress.node] ?: orchestratorProgress.node
                    Text(
                        text = nodeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Goal X/Y
                    if (orchestratorProgress.totalGoals > 0) {
                        Text(
                            text = "Cíl ${orchestratorProgress.goalIndex}/${orchestratorProgress.totalGoals}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    // Step X/Y
                    if (orchestratorProgress.totalSteps > 0) {
                        Text(
                            text = "Krok ${orchestratorProgress.stepIndex}/${orchestratorProgress.totalSteps}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                // Progress bar
                if (orchestratorProgress.percent > 0) {
                    LinearProgressIndicator(
                        progress = { (orchestratorProgress.percent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                        modifier =
                            Modifier
                                .padding(start = 24.dp, end = 48.dp)
                                .fillMaxWidth()
                                .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    } else if (message.messageType == ChatMessage.MessageType.BACKGROUND_RESULT) {
        // Standalone background result (no taskId — old messages without threading)
        val isSuccess = message.metadata["success"] != "false"
        var expanded by remember { mutableStateOf(false) }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = message.metadata["taskTitle"] ?: "Background úloha",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Skrýt" else "Zobrazit",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = !expanded) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    SelectionContainer {
                        Markdown(
                            content = message.text,
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onSurfaceVariant,
                                codeBackground = MaterialTheme.colorScheme.surface,
                            ),
                            typography = markdownTypography(
                                text = MaterialTheme.typography.bodySmall,
                                code = MaterialTheme.typography.bodySmall,
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // Timestamp
                message.timestamp?.let { ts ->
                    if (ts.isNotBlank()) {
                        Text(
                            text = formatMessageTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    } else if (message.messageType == ChatMessage.MessageType.URGENT_ALERT) {
        // Urgent alert — always expanded, errorContainer border
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Upozornění",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                SelectionContainer {
                    Markdown(
                        content = message.text,
                        colors =
                            markdownColor(
                                text = MaterialTheme.colorScheme.onErrorContainer,
                                codeBackground = MaterialTheme.colorScheme.errorContainer,
                            ),
                        typography =
                            markdownTypography(
                                text = MaterialTheme.typography.bodyMedium,
                                code = MaterialTheme.typography.bodySmall,
                            ),
                    )
                }

                // Suggested action
                message.metadata["suggested_action"]?.let { action ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Doporučení: $action",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }

                // Timestamp
                message.timestamp?.let { ts ->
                    if (ts.isNotBlank()) {
                        Text(
                            text = formatMessageTime(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    } else {
        // standard chat bubble - iMessage/WhatsApp style (width based on content)
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubbleWidth = maxWidth - 32.dp // Account for LazyColumn's padding

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
                    modifier =
                        Modifier
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

                        // Reply context indicator — when this message is a reply to a background task
                        if (isMe && message.metadata["contextTaskId"] != null) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.Reply,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                )
                                Text(
                                    text = "Reakce na background úlohu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                )
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
                                // Sanitize: normalize line endings to prevent AST/text length mismatch
                                // (StringIndexOutOfBoundsException in markdown parser)
                                val stableContent =
                                    remember(message.text) {
                                        message.text
                                            .replace("\r\n", "\n")
                                            .replace("\r", "\n")
                                            .replace("\u0000", "")
                                    }
                                Markdown(
                                    content = stableContent,
                                    colors =
                                        markdownColor(
                                            text = MaterialTheme.colorScheme.onSecondaryContainer,
                                            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    typography =
                                        markdownTypography(
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

                        // Confidence badge for assistant messages (E14-S4)
                        if (!isMe) {
                            ConfidenceBadge(message.metadata)
                        }

                        // Show workflow steps for Assistant messages
                        if (!isMe && message.workflowSteps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            WorkflowStepsDisplay(message.workflowSteps)
                        }
                    }
                }
            }
        } // BoxWithConstraints
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
                        tint =
                            when (step.status) {
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
                                expandedStepIndices =
                                    if (index in expandedStepIndices) {
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
                        modifier =
                            Modifier
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

/**
 * E14-S4: Confidence badge showing fact-check verification status.
 * Reads fact_check_confidence, fact_check_claims, fact_check_verified from metadata.
 */
@Composable
private fun ConfidenceBadge(
    metadata: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val confidenceStr = metadata["fact_check_confidence"] ?: return
    val confidence = confidenceStr.toDoubleOrNull() ?: return
    val claims = metadata["fact_check_claims"]?.toIntOrNull() ?: 0
    val verified = metadata["fact_check_verified"]?.toIntOrNull() ?: 0
    if (claims == 0) return

    val badgeColor =
        when {
            confidence >= 0.8 -> Color(0xFF4CAF50)

            // Green
            confidence >= 0.5 -> Color(0xFFFFC107)

            // Amber
            else -> Color(0xFFF44336) // Red
        }
    val pct = (confidence * 100).toInt()

    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Verified,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = badgeColor,
        )
        Text(
            text = "$pct% ($verified/$claims)",
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
        )
    }
}
