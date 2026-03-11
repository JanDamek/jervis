package com.jervis.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.ui.SafeMarkdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.jervis.dto.graph.GraphEdgeDto
import com.jervis.dto.graph.GraphVertexDto
import com.jervis.dto.graph.TaskGraphDto
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Section showing a task decomposition graph.
 *
 * When embedded in a chat bubble (default) — collapsible with header toggle.
 * When used as the main panel content (alwaysExpanded=true) — no wrapper, direct content.
 */
@Composable
fun TaskGraphSection(
    graph: TaskGraphDto,
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false,
    showOnlyActiveClients: Boolean = false,
    onOpenSubGraph: ((subGraphId: String) -> Unit)? = null,
    onOpenLiveLog: ((taskId: String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(alwaysExpanded) }

    // Pre-compute sorted vertices and which hierarchy nodes have children
    val sortedVertices = remember(graph.vertices, graph.rootVertexId, showOnlyActiveClients) {
        val allVertices = buildTreeOrder(graph.vertices.values.toList(), graph.rootVertexId)
        if (showOnlyActiveClients) {
            filterActiveClients(allVertices, graph.vertices)
        } else {
            allVertices
        }
    }
    val hierarchyWithChildren = remember(sortedVertices) {
        computeNonEmptyHierarchy(sortedVertices)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!alwaysExpanded) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            // Collapsible header for embedded graphs
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = graphSummaryLine(graph),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Graph content
        AnimatedVisibility(visible = expanded) {
            GraphContent(
                graph = graph,
                sortedVertices = sortedVertices,
                hierarchyWithChildren = hierarchyWithChildren,
                showStats = !alwaysExpanded,
                onOpenSubGraph = onOpenSubGraph,
                onOpenLiveLog = onOpenLiveLog,
            )
        }
    }
}

/**
 * Inner graph content — vertices with collapsible hierarchy.
 */
@Composable
private fun GraphContent(
    graph: TaskGraphDto,
    sortedVertices: List<GraphVertexDto>,
    hierarchyWithChildren: Set<String>,
    showStats: Boolean,
    onOpenSubGraph: ((subGraphId: String) -> Unit)?,
    onOpenLiveLog: ((taskId: String) -> Unit)?,
) {
    // Track collapsed hierarchy nodes
    val collapsedNodes = remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showStats) {
            GraphStatsRow(graph)
            Spacer(modifier = Modifier.height(2.dp))
        }

        sortedVertices.forEach { vertex ->
            // Check if any ancestor is collapsed → skip this vertex
            if (isUnderCollapsed(vertex, sortedVertices, collapsedNodes.value)) {
                return@forEach
            }

            val isHierarchy = vertex.vertexType in setOf("client", "group", "project")

            if (isHierarchy) {
                // Skip hierarchy nodes with no task children
                if (vertex.id !in hierarchyWithChildren) return@forEach

                val isCollapsed = vertex.id in collapsedNodes.value
                // Client vertices always at root indent (depth 0), regardless of stored depth
                val indent = if (vertex.vertexType == "client") 0 else (vertex.depth - 1).coerceAtLeast(0)
                HierarchyHeader(
                    vertex = vertex,
                    depthIndent = indent,
                    isCollapsed = isCollapsed,
                    onClick = {
                        collapsedNodes.value = if (isCollapsed) {
                            collapsedNodes.value - vertex.id
                        } else {
                            collapsedNodes.value + vertex.id
                        }
                    },
                )
                return@forEach
            }

            val incomingEdges = remember(graph.edges, vertex.id) {
                graph.edges.filter { it.targetId == vertex.id }
            }
            VertexCard(
                vertex = vertex,
                incomingEdges = incomingEdges,
                depthIndent = vertex.depth,
                onOpenSubGraph = onOpenSubGraph,
                onOpenLiveLog = onOpenLiveLog,
            )
        }
    }
}

/**
 * One-line graph summary: status, vertex count, token count.
 */
private fun graphSummaryLine(graph: TaskGraphDto): String {
    val vCount = graph.vertices.size
    val statusLabel = statusLabel(graph.status)
    val typeName = when (graph.graphType) {
        "memory_map" -> "Paměťová mapa"
        "thinking_map" -> "Myšlenková mapa"
        else -> "Graf"
    }
    return "$typeName: $statusLabel — $vCount vrcholů, ${graph.totalLlmCalls} LLM volání, ${formatTokens(graph.totalTokenCount)} tokenů"
}

/**
 * Graph-level statistics row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphStatsRow(
    graph: TaskGraphDto,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatChip("Stav", statusLabel(graph.status))
        StatChip("Vrcholy", graph.vertices.size.toString())
        StatChip("Hrany", graph.edges.size.toString())
        StatChip("LLM volání", graph.totalLlmCalls.toString())
        StatChip("Tokeny", formatTokens(graph.totalTokenCount))
        if (graph.projectId != null) {
            StatChip("Projekt", graph.projectId!!)
        }
    }
}

/**
 * Small chip displaying a label-value pair.
 */
@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Expandable vertex card with depth-based indentation.
 * Collapsed: type icon + title + status. Expanded: description, tools, stats, incoming edges.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VertexCard(
    vertex: GraphVertexDto,
    incomingEdges: List<GraphEdgeDto>,
    depthIndent: Int,
    modifier: Modifier = Modifier,
    onOpenSubGraph: ((subGraphId: String) -> Unit)? = null,
    onOpenLiveLog: ((taskId: String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val indentDp = (depthIndent * 16).dp

    // Compute sub-graph ID for task_ref vertices (used for header link)
    val subGraphId = when {
        vertex.localContext.startsWith("tg-") -> vertex.localContext
        vertex.vertexType == "task_ref" && vertex.inputRequest.isNotBlank() -> vertex.inputRequest
        else -> null
    }
    val hasSubGraph = subGraphId != null && onOpenSubGraph != null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = vertexContainerColor(vertex.status),
        ),
        border = CardDefaults.outlinedCardBorder(),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indentDp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    vertexTypeIcon(vertex.vertexType),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = statusColor(vertex.status),
                )
                // Title: task_ref with sub-graph → click opens thinking map; others → expand/collapse
                Text(
                    text = vertex.title.ifBlank { vertex.vertexType },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (hasSubGraph) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).clickable {
                        if (hasSubGraph) {
                            onOpenSubGraph!!(subGraphId!!)
                        } else {
                            expanded = !expanded
                        }
                    },
                )
                // Sub-graph icon for task_ref (always visible, clickable)
                if (hasSubGraph) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = "Myšlenková mapa",
                        modifier = Modifier.size(14.dp).clickable { onOpenSubGraph!!(subGraphId!!) },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // Live log icon for running coding tasks (visible on header without expanding)
                if (vertex.vertexType == "task_ref" && vertex.status == "running" && vertex.agentName == "coding" && onOpenLiveLog != null) {
                    val taskIdForLog = vertex.inputRequest.ifBlank { null }
                    if (taskIdForLog != null) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Živý výstup",
                            modifier = Modifier.size(16.dp).clickable { onOpenLiveLog(taskIdForLog) },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Status badge
                Text(
                    text = statusLabel(vertex.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(vertex.status),
                )
                // Expand/collapse arrow
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).clickable { expanded = !expanded },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }

            // Expanded content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Description
                    if (vertex.description.isNotBlank()) {
                        Text(
                            text = vertex.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Debug stats
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (vertex.agentName != null) {
                            StatChip("Agent", vertex.agentName!!)
                        }
                        StatChip("Typ", vertexTypeLabel(vertex.vertexType))
                        StatChip("Hloubka", vertex.depth.toString())
                        StatChip("Tokeny", formatTokens(vertex.tokenCount))
                        StatChip("LLM", vertex.llmCalls.toString())
                        if (vertex.toolsUsed.isNotEmpty()) {
                            StatChip("Nástroje", vertex.toolsUsed.joinToString(", "))
                        }
                    }

                    // Timing — start time + duration
                    if (vertex.startedAt != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatChip("Začátek", formatTimestamp(vertex.startedAt!!))
                            if (vertex.completedAt != null) {
                                val duration = formatDuration(vertex.startedAt!!, vertex.completedAt!!)
                                if (duration != null) {
                                    StatChip("Trvání", duration)
                                }
                            }
                        }
                    }

                    // Error
                    if (vertex.error != null) {
                        Text(
                            text = "Chyba: ${vertex.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Input request — hide raw task IDs for task_ref (not useful for user)
                    if (vertex.inputRequest.isNotBlank() && vertex.vertexType != "task_ref") {
                        ExpandableTextSection("Vstupní požadavek", vertex.inputRequest)
                    }

                    // Result summary / full result
                    if (vertex.resultSummary.isNotBlank()) {
                        ExpandableTextSection("Souhrn výsledku", vertex.resultSummary)
                    }
                    if (vertex.result.isNotBlank() && vertex.result != vertex.resultSummary) {
                        ExpandableTextSection("Plný výsledek", vertex.result)
                    }

                    // Sub-graph link in detail (also on header, but repeated here for discoverability)
                    if (hasSubGraph) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp).clickable { onOpenSubGraph!!(subGraphId!!) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.AccountTree,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Zobrazit myšlenkovou mapu",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // Local context (non-task_ref only)
                    if (vertex.localContext.isNotBlank() && vertex.vertexType != "task_ref") {
                        ExpandableTextSection("Lokální kontext", vertex.localContext)
                    }

                    // Live log button for running coding tasks
                    if (vertex.vertexType == "task_ref" && vertex.status == "running" && vertex.agentName == "coding" && onOpenLiveLog != null) {
                        val taskIdForLog = vertex.inputRequest.ifBlank { null }
                        if (taskIdForLog != null) {
                            androidx.compose.material3.TextButton(
                                onClick = { onOpenLiveLog(taskIdForLog) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Živý výstup",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    // Incoming edges — why this vertex was triggered
                    if (incomingEdges.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Příchozí hrany:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        incomingEdges.forEach { edge ->
                            EdgeRow(edge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expandable text section — shows first line collapsed, full text expanded.
 */
@Composable
private fun ExpandableTextSection(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            SafeMarkdown(
                content = text,
                colors = markdownColor(
                    text = MaterialTheme.colorScheme.onSurfaceVariant,
                    codeBackground = MaterialTheme.colorScheme.surface,
                ),
                typography = markdownTypography(
                    text = MaterialTheme.typography.bodySmall,
                    code = MaterialTheme.typography.bodySmall,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(6.dp),
                fallbackStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Single edge row — shows source vertex title, edge type, and payload summary.
 */
@Composable
private fun EdgeRow(
    edge: GraphEdgeDto,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        val sourceTitle = edge.payload?.sourceVertexTitle ?: edge.sourceId
        Text(
            text = "$sourceTitle (${edgeTypeLabel(edge.edgeType)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (edge.payload?.summary?.isNotBlank() == true) {
            Text(
                text = "— ${edge.payload!!.summary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Section header for CLIENT, GROUP, and PROJECT vertices — clickable row to collapse/expand children.
 */
@Composable
private fun HierarchyHeader(
    vertex: GraphVertexDto,
    depthIndent: Int,
    isCollapsed: Boolean = false,
    onClick: () -> Unit = {},
) {
    val indentDp = (depthIndent * 16).dp
    val isClient = vertex.vertexType == "client"
    val isGroup = vertex.vertexType == "group"

    val icon = when {
        isClient -> Icons.Default.Business
        isGroup -> Icons.Default.AccountTree
        else -> Icons.Default.Folder
    }
    val tint = when {
        isClient -> MaterialTheme.colorScheme.primary
        isGroup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val style = when {
        isClient -> MaterialTheme.typography.titleSmall
        isGroup -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentDp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint.copy(alpha = 0.6f),
        )
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Text(
            text = vertex.title,
            style = style,
            color = tint,
        )
    }
}

// --- Helpers ---

@Composable
private fun vertexContainerColor(status: String): Color {
    return when (status) {
        "completed" -> MaterialTheme.colorScheme.surface
        "running" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        "failed" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        "cancelled" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }
}

@Composable
internal fun statusColor(status: String): Color {
    return when (status) {
        // Vertex statuses
        "completed" -> MaterialTheme.colorScheme.primary
        "running" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        "cancelled", "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant
        "ready" -> MaterialTheme.colorScheme.secondary
        "pending" -> MaterialTheme.colorScheme.onSurfaceVariant
        "blocked" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        // Graph-level statuses
        "building" -> MaterialTheme.colorScheme.tertiary
        "executing" -> MaterialTheme.colorScheme.tertiary
        else -> error("Unknown status: $status")
    }
}

internal fun statusLabel(status: String): String = when (status) {
    // Vertex statuses
    "completed" -> "Dokončeno"
    "running" -> "Probíhá"
    "failed" -> "Selhalo"
    "cancelled" -> "Zrušeno"
    "pending" -> "Čeká"
    "ready" -> "Připraveno"
    "skipped" -> "Přeskočeno"
    "blocked" -> "Blokováno"
    // Graph-level statuses
    "building" -> "Sestavování"
    "executing" -> "Vykonávání"
    else -> error("Unknown status: $status")
}

private fun vertexTypeIcon(type: String): ImageVector = when (type) {
    "client" -> Icons.Default.Business
    "group" -> Icons.Default.AccountTree
    "project" -> Icons.Default.Folder
    "request" -> Icons.Default.ArrowForward
    "incoming" -> Icons.Default.Notifications
    "task_ref" -> Icons.Default.PlayArrow
    "root" -> Icons.Default.AccountTree
    "planner", "decompose" -> Icons.Default.AccountTree
    "investigator" -> Icons.Default.Schedule
    "executor", "task" -> Icons.Default.PlayArrow
    "validator" -> Icons.Default.CheckCircle
    "reviewer" -> Icons.Default.Build
    "synthesis" -> Icons.Default.CheckCircle
    "gate" -> Icons.Default.HourglassEmpty
    "setup" -> Icons.Default.Settings
    "ask_user" -> Icons.Default.QuestionAnswer
    else -> error("Unknown vertex type: $type")
}

private fun vertexTypeLabel(type: String): String = when (type) {
    "client" -> "Klient"
    "group" -> "Skupina"
    "project" -> "Projekt"
    "request" -> "Požadavek"
    "incoming" -> "Příchozí"
    "task_ref" -> "Úloha"
    "root" -> "Kořen"
    "planner", "decompose" -> "Plánovač"
    "investigator" -> "Průzkumník"
    "executor", "task" -> "Exekutor"
    "validator" -> "Validátor"
    "reviewer" -> "Recenzent"
    "synthesis" -> "Syntéza"
    "gate" -> "Brána"
    "setup" -> "Setup"
    "ask_user" -> "Dotaz"
    else -> error("Unknown vertex type: $type")
}

private fun edgeTypeLabel(type: String): String = when (type) {
    "dependency" -> "závislost"
    "decomposition" -> "dekompozice"
    "sequence" -> "sekvence"
    "context" -> "kontext"
    "result" -> "výsledek"
    "validation" -> "validace"
    else -> error("Unknown edge type: $type")
}

/** Sort priority: lower = shown first. Running → pending → completed → failed. */
private fun statusSortPriority(status: String): Int = when (status) {
    "running" -> 0
    "ready" -> 1
    "pending" -> 2
    "blocked" -> 3
    "completed" -> 4
    "failed" -> 5
    "cancelled", "skipped" -> 6
    else -> 7
}

private fun formatTokens(count: Int): String {
    return if (count >= 1000) "${count / 1000}k" else count.toString()
}

internal fun formatTimestamp(value: String): String {
    // Handle ISO timestamp (contains 'T')
    val timeStart = value.indexOf('T')
    if (timeStart >= 0) {
        val timePart = value.substring(timeStart + 1)
        return timePart.take(8) // HH:mm:ss
    }
    // Handle epoch seconds (all digits) — show as HH:mm:ss in local timezone
    val epochSec = value.toLongOrNull()
    if (epochSec != null && epochSec > 1_000_000_000) {
        val tz = TimeZone.currentSystemDefault()
        val instant = Instant.fromEpochSeconds(epochSec)
        val local = instant.toLocalDateTime(tz)
        return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
    }
    return value
}

private fun parseEpochSeconds(value: String): Long? {
    // ISO → parse to epoch
    val timeStart = value.indexOf('T')
    if (timeStart >= 0) {
        return try {
            Instant.parse(value).epochSeconds
        } catch (_: Exception) {
            null
        }
    }
    // Epoch digits
    return value.toLongOrNull()?.takeIf { it > 1_000_000_000 }
}

private fun formatDuration(startValue: String, endValue: String): String? {
    val startSec = parseEpochSeconds(startValue) ?: return null
    val endSec = parseEpochSeconds(endValue) ?: return null
    val diffSec = endSec - startSec
    if (diffSec < 0) return null
    return when {
        diffSec < 60 -> "${diffSec}s"
        diffSec < 3600 -> "${diffSec / 60}m ${diffSec % 60}s"
        else -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m"
    }
}

/**
 * Compute which hierarchy nodes (client/group/project) have at least one non-hierarchy descendant.
 * Hierarchy nodes not in this set should be hidden (empty sections).
 */
private fun computeNonEmptyHierarchy(sortedVertices: List<GraphVertexDto>): Set<String> {
    val hierarchyTypes = setOf("client", "group", "project")
    val result = mutableSetOf<String>()

    // For each non-hierarchy vertex, walk up the parent chain marking ancestors as non-empty
    val vertexMap = sortedVertices.associateBy { it.id }
    for (v in sortedVertices) {
        if (v.vertexType in hierarchyTypes) continue
        var parentId = v.parentId
        while (parentId != null) {
            val parent = vertexMap[parentId]
            if (parent != null && parent.vertexType in hierarchyTypes) {
                result.add(parent.id)
            }
            parentId = parent?.parentId
        }
    }
    return result
}

/**
 * Check if a vertex is under a collapsed hierarchy node.
 */
private fun isUnderCollapsed(
    vertex: GraphVertexDto,
    allVertices: List<GraphVertexDto>,
    collapsedNodes: Set<String>,
): Boolean {
    if (collapsedNodes.isEmpty()) return false
    val vertexMap = allVertices.associateBy { it.id }
    var parentId = vertex.parentId
    while (parentId != null) {
        if (parentId in collapsedNodes) return true
        parentId = vertexMap[parentId]?.parentId
    }
    return false
}

/**
 * Filter to show only CLIENT sub-trees that have at least one active (non-completed) vertex.
 * Active = running, blocked, pending, ready, failed — anything that represents ongoing work.
 * Clients with ONLY completed vertices are hidden (past work, no longer relevant).
 *
 * This naturally handles multi-client requests: if a chat request triggers work for nUFO + BMS,
 * both appear because they have running/pending vertices. Clients with only old completed work
 * are hidden, keeping the map focused on what's currently relevant.
 */
private fun filterActiveClients(
    sortedVertices: List<GraphVertexDto>,
    allVerticesMap: Map<String, GraphVertexDto>,
): List<GraphVertexDto> {
    val hierarchyTypes = setOf("client", "group", "project")
    val completedStatuses = setOf("completed", "done")

    // Find CLIENT vertices that have at least one non-completed, non-hierarchy descendant
    val activeClientIds = mutableSetOf<String>()

    for (v in sortedVertices) {
        if (v.vertexType in hierarchyTypes) continue
        if (v.status.lowercase() in completedStatuses) continue
        // This vertex is active — find its CLIENT ancestor
        var parentId = v.parentId
        while (parentId != null) {
            val parent = allVerticesMap[parentId]
            if (parent?.vertexType == "client") {
                activeClientIds.add(parent.id)
                break
            }
            parentId = parent?.parentId
        }
    }

    if (activeClientIds.isEmpty()) return sortedVertices // nothing active → show all (fallback)

    // Keep only vertices under active clients
    val keepIds = mutableSetOf<String>()
    keepIds.addAll(activeClientIds)

    for (v in sortedVertices) {
        if (v.id in keepIds) continue
        if (v.parentId in keepIds) {
            keepIds.add(v.id)
        }
    }

    return sortedVertices.filter { it.id in keepIds }
}

/**
 * Build DFS tree-walk order from vertices using parentId.
 * Root vertex is excluded from the result.
 * Orphan vertices (no parent, not root) are appended at the end.
 */
private fun buildTreeOrder(
    vertices: List<GraphVertexDto>,
    rootVertexId: String,
): List<GraphVertexDto> {
    // Normalize: client vertices without valid parent → treat as children of root
    val byParent = vertices.groupBy {
        if (it.vertexType == "client") rootVertexId
        else it.parentId ?: ""
    }
    val result = mutableListOf<GraphVertexDto>()
    val visited = mutableSetOf<String>()

    fun walk(parentId: String) {
        val children = byParent[parentId] ?: return
        // Sort: running first, then pending/ready, then completed/failed (newest first within each group)
        for (child in children.sortedWith(compareBy<GraphVertexDto> { statusSortPriority(it.status) }
            .thenByDescending { it.startedAt ?: it.completedAt ?: "0" })) {
            if (child.id in visited) continue
            visited.add(child.id)
            result.add(child)
            walk(child.id)
        }
    }

    // Walk from root's children (root itself is hidden)
    walk(rootVertexId)

    // Orphan vertices (parentId is null/empty and not root) — depth=1, no parent link
    for (v in vertices) {
        if (v.id !in visited && v.vertexType != "root") {
            result.add(v)
        }
    }

    return result
}
