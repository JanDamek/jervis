package com.jervis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing

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
    foregroundQueue: List<com.jervis.ui.model.PendingQueueItem>,
    backgroundQueue: List<com.jervis.ui.model.PendingQueueItem>,
    taskHistory: List<com.jervis.ui.model.TaskHistoryEntry>,
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
