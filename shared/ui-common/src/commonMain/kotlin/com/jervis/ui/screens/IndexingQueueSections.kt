package com.jervis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.CapabilityGroupDto
import com.jervis.dto.indexing.ClientItemGroupDto
import com.jervis.dto.indexing.ConnectionIndexingGroupDto
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.PipelineItemDto
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.getCapabilityLabel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

// ── Connection Group Card (hierarchical: connection → capability → client) ──

@Composable
internal fun ConnectionGroupCard(
    group: ConnectionIndexingGroupDto,
    onIntervalClick: (ConnectionCapability) -> Unit,
    onTriggerNow: (connectionId: String, capability: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    JCard {
        // Header row: connection name + provider icon + total count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(JervisSpacing.sectionPadding)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = group.providerIcon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            Text(
                text = group.connectionName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "${group.totalItemCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = JervisSpacing.itemGap),
            )

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded: capability groups
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(
                    start = JervisSpacing.sectionPadding,
                    end = JervisSpacing.sectionPadding,
                    bottom = JervisSpacing.sectionPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                for (capGroup in group.capabilityGroups) {
                    CapabilityGroupSection(
                        capGroup = capGroup,
                        connectionId = group.connectionId,
                        onIntervalClick = onIntervalClick,
                        onTriggerNow = { onTriggerNow(group.connectionId, capGroup.capability) },
                    )
                }
            }
        }
    }
}

// ── Capability Group Section (within connection) ──

@Composable
private fun CapabilityGroupSection(
    capGroup: CapabilityGroupDto,
    connectionId: String,
    onIntervalClick: (ConnectionCapability) -> Unit,
    onTriggerNow: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    val capability = try {
        ConnectionCapability.valueOf(capGroup.capability)
    } catch (_: Exception) {
        null
    }

    Column {
        // Capability header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = capabilityIcon(capGroup.capability),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            Text(
                text = capability?.let { getCapabilityLabel(it) } ?: capGroup.capability,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )

            // Item count
            Text(
                text = "${capGroup.totalItemCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )

            // Next check time (clickable)
            if (connectionId.isNotEmpty() && capability != null) {
                Text(
                    text = formatNextCheck(capGroup.nextCheckAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onIntervalClick(capability) }
                        .padding(horizontal = 4.dp),
                )
            }

            // Trigger now button
            if (connectionId.isNotEmpty()) {
                JIconButton(
                    onClick = onTriggerNow,
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Spustit teď",
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded: client groups
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 28.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (clientGroup in capGroup.clients) {
                    ClientGroupSection(clientGroup)
                }
            }
        }
    }
}

// ── Client Group Section (within capability) ──

@Composable
private fun ClientGroupSection(clientGroup: ClientItemGroupDto) {
    var expanded by remember { mutableStateOf(clientGroup.totalItemCount <= 10) }

    Column {
        // Client header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = clientGroup.clientName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "${clientGroup.totalItemCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(4.dp))

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded: individual items
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in clientGroup.items) {
                    QueueItemRow(item)
                }
                if (clientGroup.totalItemCount > clientGroup.items.size) {
                    Text(
                        text = "…a dalších ${clientGroup.totalItemCount - clientGroup.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ── Queue Item Row (inside client group) ──

@Composable
private fun QueueItemRow(item: IndexingQueueItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.type.icon(),
            contentDescription = item.type.label(),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.width(JervisSpacing.itemGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.projectName?.let { projectName ->
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.sourceUrn?.let { urn ->
                    if (item.projectName != null) {
                        Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = formatSourceUrn(urn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.errorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(JervisSpacing.itemGap))
        Text(
            text = item.state,
            style = MaterialTheme.typography.labelSmall,
            color = when (item.state) {
                "FAILED" -> MaterialTheme.colorScheme.error
                "INDEXED" -> MaterialTheme.colorScheme.primary
                "INDEXING" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ── Pipeline Section (KB queue / execution pipeline) ──

@Composable
internal fun PipelineSection(
    title: String,
    items: List<PipelineItemDto>,
    expanded: Boolean,
    onToggle: () -> Unit,
    totalCount: Long = items.size.toLong(),
    currentPage: Int = 0,
    pageSize: Int = 20,
    onPageChange: ((Int) -> Unit)? = null,
    onPrioritize: ((String) -> Unit)? = null,
    onReorder: ((String, Int) -> Unit)? = null,
    onProcessNow: ((String) -> Unit)? = null,
    showReorderControls: Boolean = false,
    showProcessNow: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    JCard {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(JervisSpacing.sectionPadding)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = accentColor,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            HorizontalDivider()

            if (items.isEmpty()) {
                JEmptyState("Žádné položky", icon = "📋")
            } else {
                Column(
                    modifier = Modifier.padding(
                        start = JervisSpacing.sectionPadding,
                        end = JervisSpacing.sectionPadding,
                        bottom = JervisSpacing.sectionPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        PipelineItemRow(
                            item = item,
                            index = index,
                            showReorderControls = showReorderControls,
                            showProcessNow = showProcessNow,
                            onPrioritize = onPrioritize,
                            onProcessNow = onProcessNow,
                            onMoveUp = if (showReorderControls && index > 0 && onReorder != null) {
                                { item.taskId?.let { id -> onReorder(id, index) } }
                            } else {
                                null
                            },
                            onMoveDown = if (showReorderControls && index < items.size - 1 && onReorder != null) {
                                { item.taskId?.let { id -> onReorder(id, index + 2) } }
                            } else {
                                null
                            },
                        )
                    }
                }

                // Pagination controls
                if (onPageChange != null && totalCount > pageSize) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(JervisSpacing.sectionPadding),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val totalPages = ((totalCount + pageSize - 1) / pageSize).toInt()

                        TextButton(
                            onClick = { onPageChange(currentPage - 1) },
                            enabled = currentPage > 0,
                        ) {
                            Text("← Předchozí")
                        }

                        Text(
                            text = "${currentPage + 1} / $totalPages",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = JervisSpacing.itemGap),
                        )

                        TextButton(
                            onClick = { onPageChange(currentPage + 1) },
                            enabled = currentPage < totalPages - 1,
                        ) {
                            Text("Další →")
                        }
                    }
                }
            }
        }
    }
}

// ── Pipeline Item Row ──

@Composable
private fun PipelineItemRow(
    item: PipelineItemDto,
    index: Int,
    showReorderControls: Boolean,
    showProcessNow: Boolean = false,
    onPrioritize: ((String) -> Unit)?,
    onProcessNow: ((String) -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reorder controls
        if (showReorderControls) {
            Column(
                modifier = Modifier.padding(end = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (onMoveUp != null) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Posunout nahoru",
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onMoveUp() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onMoveDown != null) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Posunout dolů",
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onMoveDown() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Type icon
        Icon(
            imageVector = item.type.icon(),
            contentDescription = item.type.label(),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.width(JervisSpacing.itemGap))

        // Title and metadata
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.connectionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = item.clientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Retry info
            if (item.retryCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Pokus ${item.retryCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    item.nextRetryAt?.let { nextRetry ->
                        Text(
                            text = "· další ${formatNextCheck(nextRetry)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item.errorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Prioritize button
        if (showReorderControls && onPrioritize != null && index > 0) {
            item.taskId?.let { taskId ->
                JIconButton(
                    onClick = { onPrioritize(taskId) },
                    icon = Icons.Default.VerticalAlignTop,
                    contentDescription = "Upřednostnit",
                )
            }
        }

        // Process Now button (only for KB waiting queue)
        if (showProcessNow && onProcessNow != null) {
            item.taskId?.let { taskId ->
                JIconButton(
                    onClick = { onProcessNow(taskId) },
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Zpracovat nyní",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        // State badge
        Text(
            text = pipelineStateLabel(item.pipelineState),
            style = MaterialTheme.typography.labelSmall,
            color = pipelineStateColor(item.pipelineState),
        )

        // Created time
        item.createdAt?.let { ts ->
            Spacer(Modifier.width(4.dp))
            Text(
                text = formatRelativeTime(ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Polling Interval Dialog ──

@Composable
internal fun PollingIntervalDialog(
    capability: ConnectionCapability,
    currentIntervalMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var intervalText by remember(currentIntervalMinutes) { mutableStateOf(currentIntervalMinutes.toString()) }
    val intervalValue = intervalText.toIntOrNull()
    val isValid = intervalValue != null && intervalValue in 1..1440

    JFormDialog(
        visible = true,
        title = "Interval kontroly – ${getCapabilityLabel(capability)}",
        onConfirm = { intervalValue?.let { onConfirm(it) } },
        onDismiss = onDismiss,
        confirmText = "Uložit",
        confirmEnabled = isValid,
    ) {
        JTextField(
            value = intervalText,
            onValueChange = { intervalText = it },
            label = "Interval (minuty)",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(JervisSpacing.fieldGap))

        Text(
            text = "Platí pro všechna připojení tohoto typu (1–1440 min)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Helpers ──

internal fun IndexingItemType.icon(): ImageVector = when (this) {
    IndexingItemType.GIT_COMMIT -> Icons.Default.Code
    IndexingItemType.EMAIL -> Icons.Default.Email
    IndexingItemType.BUGTRACKER_ISSUE -> Icons.Default.BugReport
    IndexingItemType.WIKI_PAGE -> Icons.Default.Description
}

internal fun IndexingItemType.label(): String = when (this) {
    IndexingItemType.GIT_COMMIT -> "Git commit"
    IndexingItemType.EMAIL -> "Email"
    IndexingItemType.BUGTRACKER_ISSUE -> "Issue"
    IndexingItemType.WIKI_PAGE -> "Wiki"
}

private fun ConnectionIndexingGroupDto.providerIcon(): ImageVector = when {
    provider == "GIT" || capabilityGroups.any { it.capability == "REPOSITORY" } -> Icons.Default.Code
    capabilityGroups.any { it.capability == "EMAIL_READ" } -> Icons.Default.Email
    capabilityGroups.any { it.capability == "BUGTRACKER" } -> Icons.Default.BugReport
    capabilityGroups.any { it.capability == "WIKI" } -> Icons.Default.Description
    else -> Icons.Default.Description
}

private fun capabilityIcon(capability: String): ImageVector = when (capability) {
    "REPOSITORY" -> Icons.Default.Code
    "EMAIL_READ" -> Icons.Default.Email
    "BUGTRACKER" -> Icons.Default.BugReport
    "WIKI" -> Icons.Default.Description
    else -> Icons.Default.Description
}

@Composable
private fun pipelineStateLabel(state: String): String = when (state) {
    "WAITING" -> "Čeká"
    "QUALIFYING" -> "Indexuje"
    "RETRYING" -> "Opakuje"
    "READY_FOR_GPU" -> "Kvalifikováno"
    "DISPATCHED_GPU" -> "Odesláno"
    "PYTHON_ORCHESTRATING" -> "Zpracovává"
    else -> state
}

@Composable
private fun pipelineStateColor(state: String): Color = when (state) {
    "WAITING" -> MaterialTheme.colorScheme.onSurfaceVariant
    "QUALIFYING" -> MaterialTheme.colorScheme.tertiary
    "RETRYING" -> MaterialTheme.colorScheme.error
    "READY_FOR_GPU" -> MaterialTheme.colorScheme.primary
    "DISPATCHED_GPU" -> MaterialTheme.colorScheme.tertiary
    "PYTHON_ORCHESTRATING" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Format next check time as relative: "Za 5 min", "Právě teď", "Zpožděno".
 */
internal fun formatNextCheck(nextCheckAt: String?): String {
    if (nextCheckAt == null) return "–"
    return try {
        val next = Instant.parse(nextCheckAt)
        val now = Clock.System.now()
        val diff = next - now
        val minutes = diff.inWholeMinutes.toInt()
        when {
            minutes > 60 -> "Za ${minutes / 60} h ${minutes % 60} min"
            minutes > 0 -> "Za $minutes min"
            minutes > -2 -> "Právě teď"
            else -> "Zpožděno"
        }
    } catch (_: Exception) {
        "–"
    }
}

/**
 * Format ISO timestamp as relative time: "Před 5 min", "Před 2 h".
 */
internal fun formatRelativeTime(isoTimestamp: String): String {
    return try {
        val ts = Instant.parse(isoTimestamp)
        val now = Clock.System.now()
        val diff = now - ts
        val minutes = diff.inWholeMinutes.toInt()
        when {
            minutes < 1 -> "Právě teď"
            minutes < 60 -> "Před $minutes min"
            minutes < 1440 -> "Před ${minutes / 60} h"
            else -> {
                val local = ts.toLocalDateTime(TimeZone.currentSystemDefault())
                "${local.dayOfMonth}.${local.monthNumber}. ${local.hour}:${local.minute.toString().padStart(2, '0')}"
            }
        }
    } catch (_: Exception) {
        isoTimestamp
    }
}

/**
 * Format sourceUrn for display: extract the type prefix.
 */
private fun formatSourceUrn(urn: String): String {
    val prefix = urn.substringBefore("::")
    return when (prefix) {
        "jira" -> "Jira"
        "github-issue" -> "GitHub"
        "gitlab-issue" -> "GitLab"
        "confluence" -> "Confluence"
        "email" -> "Email"
        "git" -> "Git"
        else -> prefix
    }
}
