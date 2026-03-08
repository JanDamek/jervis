package com.jervis.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import com.jervis.dto.graph.GraphEdgeDto
import com.jervis.dto.graph.GraphVertexDto
import com.jervis.dto.graph.TaskGraphDto
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Expandable section showing a task decomposition graph inside a BACKGROUND_RESULT bubble.
 * Initially collapsed — shows summary line. Expanded — shows vertex tree and edges.
 */
@Composable
fun TaskGraphSection(
    graph: TaskGraphDto,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Header row — graph icon + summary + expand toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Skrýt graf" else "Zobrazit graf",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded content — graph details
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Graph-level stats
                GraphStatsRow(graph)

                Spacer(modifier = Modifier.height(2.dp))

                // Vertices — tree-walk order (DFS by parent_id), root hidden
                val sortedVertices = remember(graph.vertices, graph.rootVertexId) {
                    buildTreeOrder(graph.vertices.values.toList(), graph.rootVertexId)
                }

                sortedVertices.forEach { vertex ->
                    // CLIENT and PROJECT vertices — section headers, not cards
                    if (vertex.vertexType == "client" || vertex.vertexType == "project") {
                        HierarchyHeader(vertex = vertex, depthIndent = vertex.depth - 1)
                        return@forEach
                    }

                    val incomingEdges = remember(graph.edges, vertex.id) {
                        graph.edges.filter { it.targetId == vertex.id }
                    }
                    VertexCard(
                        vertex = vertex,
                        incomingEdges = incomingEdges,
                        depthIndent = vertex.depth,
                    )
                }
            }
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
) {
    var expanded by remember { mutableStateOf(false) }
    val indentDp = (depthIndent * 16).dp

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
            // Header: icon + title + status badge + expand toggle
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
                Text(
                    text = vertex.title.ifBlank { vertex.vertexType },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Status badge
                Text(
                    text = statusLabel(vertex.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(vertex.status),
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
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

                    // Input request — for task_ref show as "Úloha (ID)"
                    if (vertex.inputRequest.isNotBlank()) {
                        val inputLabel = when (vertex.vertexType) {
                            "task_ref" -> "Úloha"
                            else -> "Vstupní požadavek"
                        }
                        ExpandableTextSection(inputLabel, vertex.inputRequest)
                    }

                    // Result summary / full result
                    if (vertex.resultSummary.isNotBlank()) {
                        ExpandableTextSection("Souhrn výsledku", vertex.resultSummary)
                    }
                    if (vertex.result.isNotBlank() && vertex.result != vertex.resultSummary) {
                        ExpandableTextSection("Plný výsledek", vertex.result)
                    }

                    // Local context — for task_ref show as "Myšlenková mapa"
                    if (vertex.localContext.isNotBlank()) {
                        val contextLabel = when {
                            vertex.vertexType == "task_ref" && vertex.localContext.startsWith("tg-") ->
                                "Myšlenková mapa"
                            else -> "Lokální kontext"
                        }
                        ExpandableTextSection(contextLabel, vertex.localContext)
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
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(6.dp),
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
 * Section header for CLIENT and PROJECT vertices — non-collapsible label with icon.
 */
@Composable
private fun HierarchyHeader(
    vertex: GraphVertexDto,
    depthIndent: Int,
) {
    val indentDp = (depthIndent * 16).dp
    val isClient = vertex.vertexType == "client"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (isClient) Icons.Default.Business else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isClient) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
        Text(
            text = vertex.title,
            style = if (isClient) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = if (isClient) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
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
private fun statusColor(status: String): Color {
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

private fun statusLabel(status: String): String = when (status) {
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

private fun formatTokens(count: Int): String {
    return if (count >= 1000) "${count / 1000}k" else count.toString()
}

private fun formatTimestamp(value: String): String {
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
 * Build DFS tree-walk order from vertices using parentId.
 * Root vertex is excluded from the result.
 * Orphan vertices (no parent, not root) are appended at the end.
 */
private fun buildTreeOrder(
    vertices: List<GraphVertexDto>,
    rootVertexId: String,
): List<GraphVertexDto> {
    val byParent = vertices.groupBy { it.parentId ?: "" }
    val result = mutableListOf<GraphVertexDto>()
    val visited = mutableSetOf<String>()

    fun walk(parentId: String) {
        val children = byParent[parentId] ?: return
        for (child in children.sortedByDescending { it.startedAt ?: it.completedAt ?: "0" }) {
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
