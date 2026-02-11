package com.jervis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.model.NodeStatus
import com.jervis.ui.model.PendingQueueItem
import com.jervis.ui.model.TaskHistoryEntry

/** Sections of the accordion layout. */
private enum class AccordionSection(val title: String) {
    AGENT("Agent"),
    FRONTEND("Frontend"),
    BACKEND("Backend"),
    HISTORY("Historie"),
}

/**
 * Agent workload detail screen — accordion layout.
 * 4 sections, only one expanded at a time.
 * Agent expanded by default, others collapsed as bottom tabs.
 */
@Composable
fun AgentWorkloadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val runningProjectId by viewModel.runningProjectId.collectAsState()
    val runningProjectName by viewModel.runningProjectName.collectAsState()
    val runningTaskPreview by viewModel.runningTaskPreview.collectAsState()
    val runningTaskType by viewModel.runningTaskType.collectAsState()
    val foregroundQueue by viewModel.foregroundQueue.collectAsState()
    val backgroundQueue by viewModel.backgroundQueue.collectAsState()
    val orchestratorProgress by viewModel.orchestratorProgress.collectAsState()
    val runningNodes by viewModel.runningTaskNodes.collectAsState()
    val taskHistory by viewModel.taskHistory.collectAsState()

    val isRunning = runningProjectId != null && runningProjectId != "none"

    var expandedSection by remember { mutableStateOf(AccordionSection.AGENT) }

    // Refresh full queue data when screen opens
    LaunchedEffect(Unit) {
        viewModel.refreshQueues()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(
            title = "Aktivita agenta",
            onBack = onBack,
        )

        // Expanded header
        AccordionSectionHeader(
            section = expandedSection,
            isExpanded = true,
            badge = badgeCount(expandedSection, foregroundQueue, backgroundQueue, taskHistory, isRunning),
            isAgentRunning = isRunning,
            onClick = {},
        )

        // Expanded content — fills remaining space
        Box(modifier = Modifier.weight(1f)) {
            when (expandedSection) {
                AccordionSection.AGENT -> AgentSectionContent(
                    isRunning = isRunning,
                    runningProjectName = runningProjectName,
                    runningTaskPreview = runningTaskPreview,
                    runningTaskType = runningTaskType,
                    orchestratorProgress = orchestratorProgress,
                    runningNodes = runningNodes,
                    onStop = {
                        orchestratorProgress?.let { viewModel.cancelOrchestration(it.taskId) }
                    },
                )
                AccordionSection.FRONTEND -> QueueSectionContent(
                    items = foregroundQueue,
                    emptyMessage = "Žádná úloha ve frontě",
                    maxItems = 5,
                )
                AccordionSection.BACKEND -> QueueSectionContent(
                    items = backgroundQueue,
                    emptyMessage = "Žádné úlohy na pozadí",
                    maxItems = 5,
                )
                AccordionSection.HISTORY -> HistorySectionContent(
                    entries = taskHistory,
                )
            }
        }

        // Collapsed headers stacked at bottom
        HorizontalDivider()
        for (section in AccordionSection.entries) {
            if (section != expandedSection) {
                AccordionSectionHeader(
                    section = section,
                    isExpanded = false,
                    badge = badgeCount(section, foregroundQueue, backgroundQueue, taskHistory, isRunning),
                    isAgentRunning = isRunning,
                    onClick = { expandedSection = section },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Calculate badge count for a section. */
private fun badgeCount(
    section: AccordionSection,
    foregroundQueue: List<PendingQueueItem>,
    backgroundQueue: List<PendingQueueItem>,
    taskHistory: List<TaskHistoryEntry>,
    isRunning: Boolean,
): Int = when (section) {
    AccordionSection.AGENT -> 0 // Agent shows spinner/dot instead
    AccordionSection.FRONTEND -> foregroundQueue.size
    AccordionSection.BACKEND -> backgroundQueue.size
    AccordionSection.HISTORY -> taskHistory.size
}

/**
 * Shared header for expanded/collapsed accordion sections.
 * Expanded: surfaceContainerHigh background, KeyboardArrowDown icon.
 * Collapsed: surface background, KeyboardArrowRight icon.
 * AGENT section collapsed: spinner if running, dot if idle.
 */
@Composable
private fun AccordionSectionHeader(
    section: AccordionSection,
    isExpanded: Boolean,
    badge: Int,
    isAgentRunning: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isExpanded) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse icon or agent status indicator
            if (section == AccordionSection.AGENT && !isExpanded) {
                // Collapsed agent: spinner if running, dot if idle
                if (isAgentRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                    else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Rozbaleno" else "Sbaleno",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isExpanded) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Badge chip
            if (badge > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// AGENT SECTION
// ════════════════════════════════════════════════════════════════════

/** Node name to Czech label for orchestrator pipeline steps. */
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
private fun AgentSectionContent(
    isRunning: Boolean,
    runningProjectName: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    orchestratorProgress: OrchestratorProgressInfo?,
    runningNodes: List<com.jervis.ui.model.NodeEntry>,
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

        // Pipeline step list — all nodes with ✓/⟳/○ status
        if (runningNodes.isNotEmpty()) {
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

// ════════════════════════════════════════════════════════════════════
// QUEUE SECTION (Frontend / Backend)
// ════════════════════════════════════════════════════════════════════

@Composable
private fun QueueSectionContent(
    items: List<PendingQueueItem>,
    emptyMessage: String,
    maxItems: Int,
) {
    if (items.isEmpty()) {
        JEmptyState(
            message = emptyMessage,
            icon = "📭",
        )
        return
    }

    val visibleItems = items.take(maxItems)
    val remainingCount = items.size - visibleItems.size

    LazyColumn(
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

// ════════════════════════════════════════════════════════════════════
// HISTORY SECTION
// ════════════════════════════════════════════════════════════════════

@Composable
private fun HistorySectionContent(entries: List<TaskHistoryEntry>) {
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

        // Expandable node list
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

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
