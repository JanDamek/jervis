package com.jervis.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.PollingIntervalUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

private data class IntervalDialogState(
    val capability: ConnectionCapability,
    val currentIntervalMinutes: Int,
)

/** Accordion sections for the indexing queue. */
private enum class IndexingSection(val title: String) {
    SOURCES("Zdroje"),
    KB_PROCESSING("KB zpracování"),
    KB_WAITING("KB fronta"),
    KB_INDEXED("Hotovo"),
}

@Composable
fun IndexingQueueScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var dashboard by remember { mutableStateOf<IndexingDashboardDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var intervalDialog by remember { mutableStateOf<IntervalDialogState?>(null) }

    // KB pagination
    var kbPage by remember { mutableStateOf(0) }
    val kbPageSize = 20

    var expandedSection by remember { mutableStateOf(IndexingSection.SOURCES) }

    val scope = rememberCoroutineScope()

    fun loadDashboard() {
        scope.launch {
            isLoading = true
            error = null
            try {
                dashboard = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = kbPage,
                    kbPageSize = kbPageSize,
                )
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDashboard() }

    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(title = "Fronta indexace")

        when {
            isLoading && dashboard == null -> JCenteredLoading()
            error != null -> JErrorState(error!!, onRetry = { loadDashboard() })
            dashboard != null -> {
                val data = dashboard!!

                // Expanded section header
                IndexingSectionHeader(
                    section = expandedSection,
                    isExpanded = true,
                    badge = sectionBadge(expandedSection, data),
                    onClick = {},
                )

                // Expanded section content
                Box(modifier = Modifier.weight(1f)) {
                    when (expandedSection) {
                        IndexingSection.SOURCES -> SourcesSectionContent(
                            data = data,
                            repository = repository,
                            onIntervalClick = { capability, capGroup ->
                                intervalDialog = IntervalDialogState(
                                    capability = capability,
                                    currentIntervalMinutes = capGroup?.intervalMinutes ?: 30,
                                )
                            },
                            onRefresh = { loadDashboard() },
                        )
                        IndexingSection.KB_PROCESSING -> PipelineSectionContent(
                            items = data.kbProcessing,
                            emptyMessage = "Nic se nezpracovává",
                        )
                        IndexingSection.KB_WAITING -> KbWaitingSectionContent(
                            data = data,
                            repository = repository,
                            kbPage = kbPage,
                            onPageChange = { newPage ->
                                kbPage = newPage
                                loadDashboard()
                            },
                            onRefresh = { loadDashboard() },
                        )
                        IndexingSection.KB_INDEXED -> PipelineSectionContent(
                            items = data.kbIndexed,
                            emptyMessage = "Zatím nic neindexováno",
                        )
                    }
                }

                // Collapsed sections at bottom
                HorizontalDivider()
                for (section in IndexingSection.entries) {
                    if (section != expandedSection) {
                        IndexingSectionHeader(
                            section = section,
                            isExpanded = false,
                            badge = sectionBadge(section, data),
                            onClick = { expandedSection = section },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Polling interval dialog
    intervalDialog?.let { state ->
        PollingIntervalDialog(
            capability = state.capability,
            currentIntervalMinutes = state.currentIntervalMinutes,
            onConfirm = { newInterval ->
                scope.launch {
                    try {
                        repository.pollingIntervals.updateSettings(
                            PollingIntervalUpdateDto(
                                intervals = mapOf(state.capability to newInterval),
                            ),
                        )
                        intervalDialog = null
                        loadDashboard()
                    } catch (_: Exception) {
                        // Dialog stays open on error
                    }
                }
            },
            onDismiss = { intervalDialog = null },
        )
    }
}

private fun sectionBadge(section: IndexingSection, data: IndexingDashboardDto): Int = when (section) {
    IndexingSection.SOURCES -> data.connectionGroups.sumOf { it.totalItemCount }
    IndexingSection.KB_PROCESSING -> data.kbProcessingCount.toInt()
    IndexingSection.KB_WAITING -> data.kbWaitingTotalCount.toInt()
    IndexingSection.KB_INDEXED -> data.kbIndexedTotalCount.toInt()
}

@Composable
private fun IndexingSectionHeader(
    section: IndexingSection,
    isExpanded: Boolean,
    badge: Int,
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
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Rozbaleno" else "Sbaleno",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isExpanded) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

@Composable
private fun SourcesSectionContent(
    data: IndexingDashboardDto,
    repository: JervisRepository,
    onIntervalClick: (ConnectionCapability, com.jervis.dto.indexing.CapabilityGroupDto?) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    if (data.connectionGroups.isEmpty()) {
        JEmptyState("Žádné zdroje k indexaci", icon = "📋")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(JervisSpacing.itemGap),
    ) {
        items(
            items = data.connectionGroups,
            key = { it.connectionId.ifEmpty { "git-${it.connectionName}" } },
        ) { group ->
            ConnectionGroupCard(
                group = group,
                onIntervalClick = { capability ->
                    val capGroup = group.capabilityGroups.find { it.capability == capability.name }
                    onIntervalClick(capability, capGroup)
                },
                onTriggerNow = { connectionId, capability ->
                    scope.launch {
                        try {
                            repository.indexingQueue.triggerIndexNow(connectionId, capability)
                            onRefresh()
                        } catch (_: Exception) {}
                    }
                },
            )
        }
    }
}

@Composable
private fun PipelineSectionContent(
    items: List<com.jervis.dto.indexing.PipelineItemDto>,
    emptyMessage: String,
) {
    if (items.isEmpty()) {
        JEmptyState(emptyMessage, icon = "📋")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            PipelineItemCompactRow(items[index])
        }
    }
}

@Composable
private fun KbWaitingSectionContent(
    data: IndexingDashboardDto,
    repository: JervisRepository,
    kbPage: Int,
    onPageChange: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    PipelineSection(
        title = "Čeká na KB (${data.kbWaitingTotalCount})",
        items = data.kbWaiting,
        expanded = true,
        onToggle = {},
        totalCount = data.kbWaitingTotalCount,
        currentPage = data.kbPage,
        pageSize = data.kbPageSize,
        onPageChange = onPageChange,
        onPrioritize = { taskId ->
            scope.launch {
                try {
                    repository.indexingQueue.prioritizeKbQueueItem(taskId)
                    onRefresh()
                } catch (_: Exception) {}
            }
        },
        onReorder = { taskId, newPosition ->
            scope.launch {
                try {
                    repository.indexingQueue.reorderKbQueueItem(taskId, newPosition)
                    onRefresh()
                } catch (_: Exception) {}
            }
        },
        onProcessNow = { taskId ->
            scope.launch {
                try {
                    repository.indexingQueue.processKbItemNow(taskId)
                    onRefresh()
                } catch (_: Exception) {}
            }
        },
        showReorderControls = true,
        showProcessNow = true,
    )
}

/** Compact pipeline item row for processing/indexed sections. */
@Composable
private fun PipelineItemCompactRow(item: com.jervis.dto.indexing.PipelineItemDto) {
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
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
            if (item.retryCount > 0) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(4.dp))
        Text(
            text = pipelineStateLabel(item.pipelineState),
            style = MaterialTheme.typography.labelSmall,
            color = pipelineStateColor(item.pipelineState),
        )

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

@Composable
private fun pipelineStateLabel(state: String): String = when (state) {
    "WAITING" -> "Ceka"
    "QUALIFYING" -> "Indexuje"
    "RETRYING" -> "Opakuje"
    else -> state
}

@Composable
private fun pipelineStateColor(state: String): androidx.compose.ui.graphics.Color = when (state) {
    "WAITING" -> MaterialTheme.colorScheme.onSurfaceVariant
    "QUALIFYING" -> MaterialTheme.colorScheme.tertiary
    "RETRYING" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
