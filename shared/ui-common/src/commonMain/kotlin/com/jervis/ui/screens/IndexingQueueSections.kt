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
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.ConnectionIndexingGroupDto
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.KbQueueItemDto
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.getCapabilityLabel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

// ── Connection Group Card (expandable) ──

@Composable
internal fun ConnectionGroupCard(
    group: ConnectionIndexingGroupDto,
    onIntervalClick: (ConnectionCapability) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    JCard {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(JervisSpacing.sectionPadding)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Provider icon
            Icon(
                imageVector = group.providerIcon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            // Connection info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.connectionName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = group.capabilities.joinToString(", ") { cap ->
                        try {
                            getCapabilityLabel(ConnectionCapability.valueOf(cap))
                        } catch (_: Exception) {
                            cap
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Item count
            Text(
                text = "${group.totalItemCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = JervisSpacing.itemGap),
            )

            // Next check time (clickable, only for real connections)
            if (group.connectionId.isNotEmpty()) {
                val primaryCapability = group.capabilities.firstOrNull()?.let {
                    try { ConnectionCapability.valueOf(it) } catch (_: Exception) { null }
                }
                Text(
                    text = formatNextCheck(group.nextCheckAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            primaryCapability?.let { onIntervalClick(it) }
                        }
                        .padding(horizontal = 4.dp),
                )

                Spacer(Modifier.width(4.dp))
            }

            // Expand/collapse icon
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded content
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(
                    start = JervisSpacing.sectionPadding,
                    end = JervisSpacing.sectionPadding,
                    bottom = JervisSpacing.sectionPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in group.items) {
                    QueueItemRow(item)
                }
                if (group.totalItemCount > group.items.size) {
                    Text(
                        text = "…a dalších ${group.totalItemCount - group.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ── Queue Item Row (inside connection group) ──

@Composable
private fun QueueItemRow(item: IndexingQueueItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    text = item.clientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.projectName?.let { projectName ->
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Source URN badge
                item.sourceUrn?.let { urn ->
                    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // State badge
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

// ── KB Queue Section (collapsed by default) ──

@Composable
internal fun KbQueueSection(
    items: List<KbQueueItemDto>,
    totalCount: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    JCard {
        // Header row
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            Text(
                text = "Odesláno do KB ($totalCount)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expanded content
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
                    for (item in items) {
                        KbQueueItemRow(item)
                    }
                    if (totalCount > items.size) {
                        Text(
                            text = "…a dalších ${totalCount - items.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── KB Queue Item Row ──

@Composable
private fun KbQueueItemRow(item: KbQueueItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            // Source URN
            item.sourceUrn?.let { urn ->
                Text(
                    text = "KB: ${formatSourceUrn(urn)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Timing info
        Spacer(Modifier.width(JervisSpacing.itemGap))
        Column(horizontalAlignment = Alignment.End) {
            item.indexedAt?.let {
                Text(
                    text = formatRelativeTime(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.waitingDurationMinutes?.let { minutes ->
                Text(
                    text = "Čeká: ${formatDurationMinutes(minutes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    provider == "GIT" || capabilities.contains("REPOSITORY") -> Icons.Default.Code
    capabilities.contains("EMAIL_READ") -> Icons.Default.Email
    capabilities.contains("BUGTRACKER") -> Icons.Default.BugReport
    capabilities.contains("WIKI") -> Icons.Default.Description
    else -> Icons.Default.Description
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
private fun formatRelativeTime(isoTimestamp: String): String {
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
 * Format duration in minutes as human-readable: "5 min", "2 h 30 min", "3 d".
 */
private fun formatDurationMinutes(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 1440 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "${minutes / 1440} d ${(minutes % 1440) / 60} h"
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
