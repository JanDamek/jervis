package com.jervis.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.model.AgentActivityEntry
import com.jervis.ui.model.PendingQueueItem

/**
 * Agent workload detail screen.
 * Shows current agent status, dual-queue (Frontend/Backend) with reorder/move controls,
 * and in-memory activity log.
 */
@Composable
fun AgentWorkloadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val entries by viewModel.activityEntries.collectAsState()
    val runningProjectId by viewModel.runningProjectId.collectAsState()
    val runningProjectName by viewModel.runningProjectName.collectAsState()
    val runningTaskPreview by viewModel.runningTaskPreview.collectAsState()
    val runningTaskType by viewModel.runningTaskType.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val foregroundQueue by viewModel.foregroundQueue.collectAsState()
    val backgroundQueue by viewModel.backgroundQueue.collectAsState()

    val isRunning = runningProjectId != null && runningProjectId != "none"

    // Refresh full queue data when screen opens
    LaunchedEffect(Unit) {
        viewModel.refreshQueues()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(
            title = "Aktivita agenta",
            onBack = onBack,
        )

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Current status card
            item {
                CurrentStatusCard(
                    isRunning = isRunning,
                    runningProjectName = runningProjectName,
                    runningProjectId = runningProjectId,
                    runningTaskPreview = runningTaskPreview,
                    runningTaskType = runningTaskType,
                    queueSize = queueSize,
                )
            }

            // FOREGROUND queue section ("Frontend")
            item {
                QueueSectionHeader(
                    title = "Frontend",
                    count = foregroundQueue.size,
                )
            }
            if (foregroundQueue.isEmpty()) {
                item {
                    JEmptyState(
                        message = "Žádná úloha ve frontě",
                        icon = "📭",
                    )
                }
            } else {
                itemsIndexed(foregroundQueue) { index, item ->
                    QueueItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == foregroundQueue.lastIndex,
                        onMoveUp = { viewModel.moveTaskUp(item.taskId) },
                        onMoveDown = { viewModel.moveTaskDown(item.taskId) },
                        onSwitchQueue = { viewModel.moveTaskToQueue(item.taskId, "BACKGROUND") },
                        switchQueueForward = true,
                        switchQueueLabel = "Do backendu",
                    )
                }
            }

            // BACKGROUND queue section ("Backend")
            item {
                QueueSectionHeader(
                    title = "Backend",
                    count = backgroundQueue.size,
                )
            }
            if (backgroundQueue.isEmpty()) {
                item {
                    JEmptyState(
                        message = "Žádné úlohy na pozadí",
                        icon = "📭",
                    )
                }
            } else {
                itemsIndexed(backgroundQueue) { index, item ->
                    QueueItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == backgroundQueue.lastIndex,
                        onMoveUp = { viewModel.moveTaskUp(item.taskId) },
                        onMoveDown = { viewModel.moveTaskDown(item.taskId) },
                        onSwitchQueue = { viewModel.moveTaskToQueue(item.taskId, "FOREGROUND") },
                        switchQueueForward = false,
                        switchQueueLabel = "Do frontendu",
                    )
                }
            }

            // Activity history section
            item {
                QueueSectionHeader(
                    title = "Historie aktivity",
                    count = entries.size,
                )
            }

            if (entries.isEmpty()) {
                item {
                    JEmptyState(
                        message = "Zatím žádná aktivita od spuštění",
                        icon = "📋",
                    )
                }
            } else {
                // Show newest first
                items(entries.reversed()) { entry ->
                    ActivityEntryRow(entry)
                }
            }
        }
    }
}

/**
 * Section header with title and count badge.
 */
@Composable
private fun QueueSectionHeader(
    title: String,
    count: Int,
) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(horizontal = JervisSpacing.outerPadding))
        Spacer(modifier = Modifier.height(JervisSpacing.itemGap))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun CurrentStatusCard(
    isRunning: Boolean,
    runningProjectName: String?,
    runningProjectId: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    queueSize: Int,
) {
    JCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(JervisSpacing.outerPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status indicator
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (isRunning) {
                    Text(
                        text = "Zpracovává se",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            if (runningTaskType != null && runningTaskType.isNotBlank()) {
                                append("$runningTaskType | ")
                            }
                            append(runningProjectName ?: runningProjectId ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (runningTaskPreview != null && runningTaskPreview.isNotBlank()) {
                        Text(
                            text = runningTaskPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = "Agent je nečinný",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Žádná úloha se nezpracovává",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (queueSize > 0) {
                Text(
                    text = "Fronta: $queueSize",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Row displaying a single queue item with reorder and queue-switch controls.
 * All buttons have 44dp touch targets per design system requirements.
 */
@Composable
private fun QueueItemRow(
    item: PendingQueueItem,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSwitchQueue: () -> Unit,
    switchQueueForward: Boolean,
    switchQueueLabel: String,
) {
    JCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Reorder: Move up
            JIconButton(
                onClick = onMoveUp,
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Posunout nahoru",
                enabled = !isFirst,
            )

            // Reorder: Move down
            JIconButton(
                onClick = onMoveDown,
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Posunout dolů",
                enabled = !isLast,
            )

            // Switch queue
            JIconButton(
                onClick = onSwitchQueue,
                icon = if (switchQueueForward) Icons.AutoMirrored.Filled.ArrowForward
                else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = switchQueueLabel,
            )
        }
    }
}

@Composable
private fun ActivityEntryRow(entry: AgentActivityEntry) {
    val (icon, color) = when (entry.type) {
        AgentActivityEntry.Type.TASK_STARTED -> "▶" to MaterialTheme.colorScheme.primary
        AgentActivityEntry.Type.TASK_COMPLETED -> "✓" to MaterialTheme.colorScheme.tertiary
        AgentActivityEntry.Type.AGENT_IDLE -> "●" to MaterialTheme.colorScheme.outline
        AgentActivityEntry.Type.QUEUE_CHANGED -> "↻" to MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Timestamp
        Text(
            text = entry.time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )

        // Icon
        Text(
            text = icon,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.width(24.dp),
        )

        // Description
        Column(modifier = Modifier.weight(1f)) {
            // Show type-based label + project name on first line
            val hasTypeLabel = !entry.taskType.isNullOrBlank()
            val hasProjectName = !entry.projectName.isNullOrBlank()
            // Avoid showing duplicate text (e.g., taskType == projectName)
            val showProjectName = hasProjectName &&
                (!hasTypeLabel || entry.projectName != entry.taskType)

            if (hasTypeLabel || showProjectName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasTypeLabel) {
                        Text(
                            text = entry.taskType!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (showProjectName) {
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                    if (showProjectName) {
                        Text(
                            text = entry.projectName!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
