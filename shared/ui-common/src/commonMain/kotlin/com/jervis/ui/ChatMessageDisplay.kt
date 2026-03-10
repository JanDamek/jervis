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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.chat.TaskGraphSection
import com.jervis.ui.queue.OrchestratorProgressInfo
import com.jervis.ui.util.copyToClipboard
import com.jervis.ui.util.formatMessageTime
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Human-readable labels for orchestrator pipeline nodes. */
private val nodeLabels = mapOf(
    "intake" to "Analýza úlohy",
    "evidence" to "Shromažďování kontextu",
    "evidence_pack" to "Shromažďování kontextu",
    "plan" to "Plánování",
    "plan_steps" to "Plánování kroků",
    "execute" to "Provádění",
    "execute_step" to "Provádění kroku",
    "evaluate" to "Vyhodnocení",
    "finalize" to "Dokončení",
    "respond" to "Generování odpovědi",
    "clarify" to "Upřesnění",
    "decompose" to "Dekompozice na cíle",
    "select_goal" to "Výběr cíle",
    "advance_step" to "Další krok",
    "advance_goal" to "Další cíl",
    "git_operations" to "Git operace",
    "report" to "Generování reportu",
)

@Composable
internal fun ChatArea(
    messages: List<ChatMessage>,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    orchestratorProgress: OrchestratorProgressInfo? = null,
    onLoadMore: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onReplyToTask: (taskId: String) -> Unit = {},
    onSendReply: (taskId: String, text: String) -> Unit = { _, _ -> },
    taskGraphs: Map<String, TaskGraphDto?> = emptyMap(),
    onLoadTaskGraph: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // reverseLayout=true: item 0 is at the bottom of the screen.
    val reversedMessages = remember(messages) { messages.asReversed() }

    // Auto-load older messages when user scrolls near the top
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 2
        }
    }
    LaunchedEffect(nearTop, hasMore, isLoadingMore) {
        if (nearTop && hasMore && !isLoadingMore) {
            onLoadMore()
        }
    }

    // Show scroll-to-bottom FAB when not at bottom (reverseLayout: firstVisibleItemIndex > 2)
    val showScrollToBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    Box(modifier = modifier) {
        if (messages.isEmpty()) {
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
                items(reversedMessages.size) { index ->
                    val message = reversedMessages[index]
                    val originalIndex = messages.size - 1 - index

                    ChatMessageItem(
                        message = message,
                        orchestratorProgress = if (message.messageType == ChatMessage.MessageType.PROGRESS) orchestratorProgress else null,
                        onEditMessage = onEditMessage,
                        onReplyToTask = onReplyToTask,
                        onSendReply = onSendReply,
                        taskGraphs = taskGraphs,
                        onLoadTaskGraph = onLoadTaskGraph,
                    )

                    // Compression boundary AFTER this message (before the next older one)
                    if (originalIndex > 0) {
                        val prevSequence = messages[originalIndex - 1].sequence
                        val currSequence = message.sequence
                        if (prevSequence != null && currSequence != null) {
                            val boundary =
                                compressionBoundaries.find { b ->
                                    b.afterSequence in prevSequence until currSequence
                                }
                            if (boundary != null) {
                                CompressionBoundaryIndicator(boundary)
                            }
                        }
                    }
                }

                // "Load more" indicator at top (= end of reversed list)
                // Auto-triggers via nearTop above; clickable as fallback.
                if (hasMore) {
                    item(key = "load_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoadingMore) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Načítám starší zprávy…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Načíst starší zprávy")
                                }
                            }
                        }
                    }
                }
            }

            // Scroll-to-bottom FAB
            AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                FloatingActionButton(
                    onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(24.dp),
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

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    orchestratorProgress: OrchestratorProgressInfo? = null,
    onEditMessage: (String) -> Unit = {},
    onReplyToTask: (taskId: String) -> Unit = {},
    onSendReply: (taskId: String, text: String) -> Unit = { _, _ -> },
    taskGraphs: Map<String, TaskGraphDto?> = emptyMap(),
    onLoadTaskGraph: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isMe = message.from == ChatMessage.Sender.Me

    // Error messages with red styling + expandable details
    if (message.messageType == ChatMessage.MessageType.ERROR) {
        var showDetails by remember { mutableStateOf(false) }
        val hasDetails = message.metadata.any { it.key !in setOf("eventType", "streaming") }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.weight(1f),
                    )
                    if (hasDetails) {
                        IconButton(
                            onClick = { showDetails = !showDetails },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showDetails) "Skrýt" else "Detail",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = showDetails) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, start = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        message.metadata.forEach { (key, value) ->
                            if (key !in setOf("eventType", "streaming") && value.isNotBlank()) {
                                Row {
                                    Text(
                                        text = "$key: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (message.messageType == ChatMessage.MessageType.PROGRESS) {
        var expanded by remember { mutableStateOf(false) }
        val hasSteps = message.thinkingSteps.size > 1

        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Main progress row: spinner + text + expand toggle
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
                    modifier = Modifier.weight(1f),
                )
                if (hasSteps) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Skrýt" else "Zobrazit detail",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Expandable thinking steps
            AnimatedVisibility(visible = expanded && hasSteps) {
                Column(
                    modifier = Modifier.padding(start = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    message.thinkingSteps.forEachIndexed { index, step ->
                        val isCurrent = index == message.thinkingSteps.lastIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isCurrent) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                )
                            }
                            Text(
                                text = "${index + 1}. $step",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
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
        // Background task result — collapsible card with surfaceVariant background
        val isSuccess = message.metadata["success"] != "false"
        var expanded by remember { mutableStateOf(false) }

        Card(
            colors =
                CardDefaults.cardColors(
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
                        tint =
                            if (isSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                    Text(
                        text = message.metadata["task_title"] ?: "Background úloha",
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

                // Collapsed: first line only. Expanded: full text.
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
                        SafeMarkdown(
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
                            fallbackStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Task graph section — lazy-loaded on demand
                // Background results use "taskId", chat messages use "contextTaskId"
                val graphTaskId = message.metadata["taskId"] ?: message.metadata["contextTaskId"]
                if (graphTaskId != null) {
                    val graphEntry = taskGraphs[graphTaskId]
                    if (graphEntry != null && graphEntry.vertices.isNotEmpty()) {
                        // Graph loaded — show it
                        TaskGraphSection(graph = graphEntry)
                    } else if (graphEntry != null && graphEntry.vertices.isEmpty()) {
                        // Tried to load but no graph exists — show info
                        Text(
                            text = "Graf není k dispozici",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else if (graphTaskId !in taskGraphs) {
                        // Not loaded yet — show "load graph" button
                        TextButton(
                            onClick = { onLoadTaskGraph(graphTaskId) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(
                                Icons.Default.AccountTree,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Zobrazit graf",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        // Loading in progress (key exists, value is null)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Text(
                                "Načítání grafu…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // User response (shown inline after "Reagovat" reply)
                val hasResponse = message.userResponse != null
                if (hasResponse) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp).padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        Text(
                            text = message.userResponse!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Timestamp + Reply row
                val taskId = message.metadata["taskId"]
                var showReplyInput by remember { mutableStateOf(false) }
                var replyText by remember { mutableStateOf("") }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    message.timestamp?.let { ts ->
                        if (ts.isNotBlank()) {
                            Text(
                                text = formatMessageTime(ts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (taskId != null && !showReplyInput && !hasResponse) {
                        TextButton(
                            onClick = { showReplyInput = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Edit,
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

                // Inline reply input
                AnimatedVisibility(visible = showReplyInput && taskId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            placeholder = {
                                Text(
                                    "Napište reakci...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 88.dp),
                            maxLines = 3,
                            singleLine = false,
                        )
                        IconButton(
                            onClick = {
                                if (replyText.isNotBlank() && taskId != null) {
                                    onSendReply(taskId, replyText)
                                    replyText = ""
                                    showReplyInput = false
                                }
                            },
                            enabled = replyText.isNotBlank(),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Odeslat",
                                tint = if (replyText.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                showReplyInput = false
                                replyText = ""
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Zrušit",
                                modifier = Modifier.size(18.dp),
                            )
                        }
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
                    SafeMarkdown(
                        content = message.text,
                        colors = markdownColor(
                            text = MaterialTheme.colorScheme.onErrorContainer,
                            codeBackground = MaterialTheme.colorScheme.errorContainer,
                        ),
                        typography = markdownTypography(
                            text = MaterialTheme.typography.bodyMedium,
                            code = MaterialTheme.typography.bodySmall,
                        ),
                        fallbackStyle = MaterialTheme.typography.bodyMedium,
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

                // Timestamp + action buttons
                val alertSourceUrn = message.metadata["sourceUrn"]
                var showAlertReply by remember { mutableStateOf(false) }
                var alertReplyText by remember { mutableStateOf("") }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    message.timestamp?.let { ts ->
                        if (ts.isNotBlank()) {
                            Text(
                                text = formatMessageTime(ts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (!showAlertReply) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { showAlertReply = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Edit,
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
                }

                // Inline reply for alert
                AnimatedVisibility(visible = showAlertReply) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = alertReplyText,
                            onValueChange = { alertReplyText = it },
                            placeholder = {
                                Text(
                                    "Napište reakci...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 88.dp),
                            maxLines = 3,
                            singleLine = false,
                        )
                        IconButton(
                            onClick = {
                                if (alertReplyText.isNotBlank()) {
                                    // Send reply as context-aware chat message
                                    val context = alertSourceUrn?.let { "Re: alert ($it): " } ?: "Re: alert: "
                                    onSendReply(alertSourceUrn ?: "alert", alertReplyText)
                                    alertReplyText = ""
                                    showAlertReply = false
                                }
                            },
                            enabled = alertReplyText.isNotBlank(),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Odeslat",
                                tint = if (alertReplyText.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                showAlertReply = false
                                alertReplyText = ""
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Zrušit",
                                modifier = Modifier.size(18.dp),
                            )
                        }
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

                        Spacer(modifier = Modifier.height(6.dp))

                        // Message content - Markdown for Assistant, plain text for User
                        SelectionContainer {
                            if (isMe) {
                                // User messages - plain text
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Attachment indicator
                                val attachmentNames = message.metadata["attachments"]
                                if (!attachmentNames.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                        Text(
                                            text = attachmentNames,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            } else {
                                // Assistant messages - Markdown rendering with safe fallback
                                SafeMarkdown(
                                    content = message.text,
                                    colors = markdownColor(
                                        text = MaterialTheme.colorScheme.onSecondaryContainer,
                                        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    typography = markdownTypography(
                                        text = MaterialTheme.typography.bodyMedium,
                                        code = MaterialTheme.typography.bodySmall,
                                        h1 = MaterialTheme.typography.headlineMedium,
                                        h2 = MaterialTheme.typography.headlineSmall,
                                        h3 = MaterialTheme.typography.titleLarge,
                                    ),
                                    fallbackStyle = MaterialTheme.typography.bodyMedium,
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

/**
 * Safe Markdown renderer that pre-validates AST ranges.
 * Falls back to plain Text if the markdown parser produces
 * out-of-bounds source positions (StringIndexOutOfBoundsException).
 */
@Composable
private fun SafeMarkdown(
    content: String,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
    fallbackStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    // Sanitize line endings + validate AST ranges
    val sanitized = remember(content) {
        content.replace("\r\n", "\n").replace("\r", "\n").replace("\u0000", "").trimEnd()
    }
    val canRender = remember(sanitized) {
        try {
            val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(sanitized)
            // Recursively check all AST node ranges fit within the sanitized string
            fun valid(node: org.intellij.markdown.ast.ASTNode): Boolean =
                node.endOffset <= sanitized.length && node.children.all(::valid)
            valid(tree)
        } catch (_: Exception) {
            false
        }
    }
    if (canRender) {
        Markdown(content = sanitized, colors = colors, typography = typography, modifier = modifier)
    } else {
        Text(text = content, style = fallbackStyle, modifier = modifier)
    }
}
