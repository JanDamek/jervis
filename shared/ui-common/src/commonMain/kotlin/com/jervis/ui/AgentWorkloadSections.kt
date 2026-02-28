package com.jervis.ui

import com.jervis.ui.queue.OrchestratorProgressInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.model.NodeStatus
import com.jervis.ui.model.PendingQueueItem
import com.jervis.ui.model.TaskHistoryEntry
import kotlin.time.Clock

/** Node name to Czech label for orchestrator pipeline steps. */
internal val nodeLabels = mapOf(
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
internal fun AgentSectionContent(
    isRunning: Boolean,
    runningProjectName: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    orchestratorProgress: OrchestratorProgressInfo?,
    runningNodes: List<com.jervis.ui.model.NodeEntry>,
    recentChatMessages: List<com.jervis.dto.ui.ChatMessage>,
    onStop: () -> Unit,
) {
    if (!isRunning) {
        JEmptyState(
            message = "Agent je nečinný",
            icon = Icons.Default.HourglassEmpty,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Running task info
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Běží: ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!runningProjectName.isNullOrBlank()) {
                Text(
                    text = runningProjectName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!runningTaskType.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "/ $runningTaskType",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!runningTaskPreview.isNullOrBlank()) {
            Text(
                text = runningTaskPreview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Recent chat messages — shows last conversation context
        if (recentChatMessages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                recentChatMessages.forEach { msg ->
                    val (prefix, prefixColor) = when (msg.from) {
                        com.jervis.dto.ui.ChatMessage.Sender.Me ->
                            "Vy" to MaterialTheme.colorScheme.primary
                        com.jervis.dto.ui.ChatMessage.Sender.Assistant ->
                            "Agent" to MaterialTheme.colorScheme.tertiary
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "$prefix: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = prefixColor,
                        )
                        Text(
                            text = msg.text.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Orchestrator progress — goal/step counters and stop button
        if (orchestratorProgress != null) {
            Spacer(modifier = Modifier.height(4.dp))

            if (orchestratorProgress.totalGoals > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Cíl ${orchestratorProgress.goalIndex + 1}/${orchestratorProgress.totalGoals}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JIconButton(
                        onClick = onStop,
                        icon = Icons.Default.Close,
                        contentDescription = "Zastavit",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    JIconButton(
                        onClick = onStop,
                        icon = Icons.Default.Close,
                        contentDescription = "Zastavit",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Status message
            if (orchestratorProgress.message.isNotBlank()) {
                Text(
                    text = orchestratorProgress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Step progress bar
            if (orchestratorProgress.totalSteps > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Krok ${orchestratorProgress.stepIndex + 1}/${orchestratorProgress.totalSteps}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (orchestratorProgress.percent > 0) {
                        Text(
                            text = "${orchestratorProgress.percent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (orchestratorProgress.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }

        // Pipeline step timeline — nodes with timestamps and durations
        val steps = orchestratorProgress?.steps ?: emptyList()
        if (steps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))

            // Elapsed time since start
            val nowMs = remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    nowMs.value = Clock.System.now().toEpochMilliseconds()
                }
            }
            val elapsed = formatDurationMs(nowMs.value - (orchestratorProgress?.startedAtMs ?: nowMs.value))
            Text(
                text = "Celkový čas: $elapsed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    val isLast = index == steps.lastIndex
                    val isRunning = isLast // last step is always the current one
                    val (icon, color) = if (isRunning) {
                        "⟳" to MaterialTheme.colorScheme.primary
                    } else {
                        "✓" to MaterialTheme.colorScheme.tertiary
                    }
                    // Duration: for completed steps, diff to next; for running, diff to now
                    val durationMs = if (isRunning) {
                        nowMs.value - step.timestampMs
                    } else {
                        steps[index + 1].timestampMs - step.timestampMs
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                    ) {
                        Text(
                            text = icon,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.width(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${step.node} — ${step.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatDurationMs(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (runningNodes.isNotEmpty()) {
            // Fallback: simple node list without timestamps (pre-existing behavior)
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                runningNodes.forEach { node ->
                    val (icon, color) = when (node.status) {
                        NodeStatus.DONE -> "✓" to MaterialTheme.colorScheme.tertiary
                        NodeStatus.RUNNING -> "⟳" to MaterialTheme.colorScheme.primary
                        NodeStatus.PENDING -> "○" to MaterialTheme.colorScheme.outline
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(min = 24.dp),
                    ) {
                        Text(
                            text = icon,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            text = "${node.node} — ${node.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QueueSectionContent(
    items: List<PendingQueueItem>,
    emptyMessage: String,
    initialVisible: Int = 20,
) {
    if (items.isEmpty()) {
        JEmptyState(
            message = emptyMessage,
            icon = "📭",
        )
        return
    }

    var visibleCount by remember { mutableStateOf(initialVisible) }
    val listState = rememberLazyListState()

    // Load more when scrolled near end
    LaunchedEffect(listState, visibleCount) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= visibleCount - 5
        }.collect { nearEnd ->
            if (nearEnd && visibleCount < items.size) {
                visibleCount = minOf(visibleCount + 20, items.size)
            }
        }
    }

    val visibleItems = items.take(visibleCount)
    val remainingCount = items.size - visibleItems.size

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(visibleItems, key = { it.taskId.ifEmpty { it.preview } }) { item ->
            CompactQueueItemRow(item)
        }
        if (remainingCount > 0) {
            item {
                Text(
                    text = "... a dalších $remainingCount úloh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * Backend queue section with DB-level pagination (infinite scroll).
 * Shows first 20 items from server, loads more pages on scroll.
 */
@Composable
internal fun BackendQueueSectionContent(
    items: List<PendingQueueItem>,
    totalCount: Long,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty() && totalCount == 0L) {
        JEmptyState(
            message = "Žádné úlohy na pozadí",
            icon = "📭",
        )
        return
    }

    val listState = rememberLazyListState()

    // Infinite scroll: load more when near end
    LaunchedEffect(listState, items.size) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= items.size - 5
        }.collect { nearEnd ->
            if (nearEnd && items.size.toLong() < totalCount) {
                onLoadMore()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.taskId.ifEmpty { it.preview } }) { item ->
            CompactQueueItemRow(item)
        }
        if (isLoadingMore) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        if (items.size.toLong() < totalCount && !isLoadingMore) {
            item {
                Text(
                    text = "... a dalších ${totalCount - items.size} úloh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactQueueItemRow(item: PendingQueueItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        // Type + project (labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.taskType.isNotBlank()) {
                Text(
                    text = item.taskType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = item.projectName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Preview (bodySmall, 1 line, ellipsis)
        Text(
            text = item.preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun HistorySectionContent(
    entries: List<TaskHistoryEntry>,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    if (entries.isEmpty()) {
        JEmptyState(
            message = "Zatím žádná aktivita",
            icon = "📋",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Use index-based key — same taskId can appear multiple times
        // (e.g., interrupted → resumed → completed)
        items(entries.size, key = { index -> "${entries[index].taskId}_$index" }) { index ->
            TaskHistoryItem(entries[index])
            // Trigger load more when reaching last 3 items
            if (hasMore && index >= entries.size - 3) {
                LaunchedEffect(entries.size) {
                    onLoadMore()
                }
            }
        }
    }
}

@Composable
private fun TaskHistoryItem(entry: TaskHistoryEntry) {
    var expanded by remember { mutableStateOf(entry.status == "running") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        // Header: preview + time range
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!entry.projectName.isNullOrBlank()) {
                    Text(
                        text = entry.projectName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = entry.taskPreview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(entry.startTime)
                    if (entry.endTime != null) {
                        append(" – ")
                        append(entry.endTime)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expandable node list with durations
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entry.nodes.forEach { node ->
                    val (icon, color) = when (node.status) {
                        NodeStatus.DONE -> "✓" to MaterialTheme.colorScheme.tertiary
                        NodeStatus.RUNNING -> "⟳" to MaterialTheme.colorScheme.primary
                        NodeStatus.PENDING -> "○" to MaterialTheme.colorScheme.outline
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                    ) {
                        Text(
                            text = icon,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.width(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${node.node} — ${node.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (node.durationMs != null && node.durationMs > 0) {
                            Text(
                                text = formatDurationMs(node.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Format milliseconds to human-readable duration (e.g. "45s", "2m 15s", "1h 3m"). */
internal fun formatDurationMs(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
