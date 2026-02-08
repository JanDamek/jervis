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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.model.AgentActivityEntry

/**
 * Agent workload detail screen.
 * Shows current agent status and in-memory activity log (since app start).
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

    val isRunning = runningProjectId != null && runningProjectId != "none"

    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(
            title = "Aktivita agenta",
            onBack = onBack,
        )

        // Current status card
        CurrentStatusCard(
            isRunning = isRunning,
            runningProjectName = runningProjectName,
            runningProjectId = runningProjectId,
            runningTaskPreview = runningTaskPreview,
            runningTaskType = runningTaskType,
            queueSize = queueSize,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = JervisSpacing.outerPadding))

        Spacer(modifier = Modifier.height(JervisSpacing.itemGap))

        // Section title
        Text(
            text = "Historie aktivity",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(JervisSpacing.itemGap))

        // Activity log
        if (entries.isEmpty()) {
            JEmptyState(
                message = "Zatím žádná aktivita od spuštění",
                icon = "📋",
            )
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = JervisSpacing.outerPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Show newest first
                items(entries.reversed()) { entry ->
                    ActivityEntryRow(entry)
                }
            }
        }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(JervisSpacing.outerPadding),
        border = CardDefaults.outlinedCardBorder(),
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
            .padding(horizontal = 6.dp, vertical = 4.dp)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.taskType != null) {
                    Text(
                        text = entry.taskType,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (entry.projectName != null) {
                    Text(
                        text = entry.projectName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
