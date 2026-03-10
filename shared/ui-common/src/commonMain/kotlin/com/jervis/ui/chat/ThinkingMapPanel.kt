package com.jervis.ui.chat

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.service.IJobLogsService
import com.jervis.ui.coding.CodingAgentLogPanel
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTopBar

/**
 * Panel showing the Paměťová mapa (Memory Map) graph alongside chat.
 *
 * Supports navigation stack:
 * - Memory map (default) → click TASK_REF → detail thinking map (with back)
 * - Task history dropdown from header
 * - Live log overlay for running coding agent tasks
 */
@Composable
fun ThinkingMapPanel(
    activeMap: TaskGraphDto?,
    isCompact: Boolean = false,
    onClose: () -> Unit = {},
    detailGraph: TaskGraphDto? = null,
    liveLogTaskId: String? = null,
    jobLogsService: IJobLogsService? = null,
    onOpenSubGraph: ((String) -> Unit)? = null,
    onCloseSubGraph: (() -> Unit)? = null,
    onOpenLiveLog: ((String) -> Unit)? = null,
    onCloseLiveLog: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val showDetail = detailGraph != null
    val showLiveLog = liveLogTaskId != null && jobLogsService != null

    val title = when {
        showDetail -> when (detailGraph?.graphType) {
            "thinking_map" -> "Myšlenková mapa"
            else -> "Detail grafu"
        }
        else -> when (activeMap?.graphType) {
            "memory_map" -> "Paměťová mapa"
            "thinking_map" -> "Myšlenková mapa"
            else -> "Mapa"
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isCompact) {
            JTopBar(
                title = title,
                onBack = if (showDetail) {
                    { onCloseSubGraph?.invoke(); Unit }
                } else {
                    onClose
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // Header: title + actions
            if (!isCompact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    // Back button when in detail view
                    if (showDetail) {
                        JIconButton(
                            onClick = { onCloseSubGraph?.invoke() },
                            icon = Icons.Default.ArrowBack,
                            contentDescription = "Zpět",
                        )
                    }

                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )

                    // Task history dropdown (only in memory map view)
                    if (!showDetail && activeMap != null) {
                        TaskHistoryDropdown(
                            activeMap = activeMap,
                            onOpenSubGraph = onOpenSubGraph,
                        )
                    }

                    JIconButton(
                        onClick = onClose,
                        icon = Icons.Default.Close,
                        contentDescription = "Zavřít",
                    )
                }
            }

            // Live log overlay (bottom portion)
            if (showLiveLog) {
                Column(modifier = Modifier.weight(1f)) {
                    // Graph (top half)
                    val graphToShow = detailGraph ?: activeMap
                    if (graphToShow != null) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            TaskGraphSection(
                                graph = graphToShow,
                                modifier = Modifier.fillMaxWidth(),
                                alwaysExpanded = true,
                                onOpenSubGraph = onOpenSubGraph,
                                onOpenLiveLog = onOpenLiveLog,
                            )
                        }
                    }

                    // Live log (bottom half)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Živý výstup",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        JIconButton(
                            onClick = { onCloseLiveLog?.invoke() },
                            icon = Icons.Default.Close,
                            contentDescription = "Zavřít výstup",
                        )
                    }
                    CodingAgentLogPanel(
                        jobLogsService = jobLogsService!!,
                        taskId = liveLogTaskId!!,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else {
                // Graph visualization (full panel)
                val graphToShow = detailGraph ?: activeMap
                if (graphToShow != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TaskGraphSection(
                            graph = graphToShow,
                            modifier = Modifier.fillMaxWidth(),
                            onOpenSubGraph = onOpenSubGraph,
                            onOpenLiveLog = onOpenLiveLog,
                        )
                    }
                } else {
                    Text(
                        text = "Žádná aktivní mapa",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Dropdown button showing recent TASK_REF vertices from the memory map.
 * Click on a task opens its thinking map sub-graph.
 */
@Composable
private fun TaskHistoryDropdown(
    activeMap: TaskGraphDto,
    onOpenSubGraph: ((String) -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }

    // Collect TASK_REF vertices sorted by startedAt desc
    val taskRefs = remember(activeMap.vertices) {
        activeMap.vertices.values
            .filter { it.vertexType == "task_ref" }
            .sortedByDescending { it.startedAt ?: it.completedAt ?: "" }
            .take(20)
    }

    if (taskRefs.isEmpty()) return

    Box {
        JIconButton(
            onClick = { expanded = true },
            icon = Icons.Default.History,
            contentDescription = "Historie úloh",
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            taskRefs.forEach { vertex ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = statusLabel(vertex.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(vertex.status),
                                )
                                Text(
                                    text = vertex.title.ifBlank { "Task" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (vertex.startedAt != null) {
                                Text(
                                    text = formatTimestamp(vertex.startedAt!!),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        val subGraphId = when {
                            vertex.localContext.startsWith("tg-") -> vertex.localContext
                            vertex.inputRequest.isNotBlank() -> vertex.inputRequest
                            else -> null
                        }
                        if (subGraphId != null && onOpenSubGraph != null) {
                            onOpenSubGraph(subGraphId)
                        }
                    },
                )
            }
        }
    }
}
