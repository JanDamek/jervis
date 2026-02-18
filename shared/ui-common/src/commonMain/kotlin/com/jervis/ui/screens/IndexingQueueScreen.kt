package com.jervis.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.PipelineItemDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.QualificationProgressInfo
import com.jervis.ui.QualificationProgressStep
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Accordion sections for the indexing queue (Zdroje removed). */
private enum class IndexingSection(val title: String) {
    KB_PROCESSING("KB zpracování"),
    KB_WAITING("KB fronta"),
    KB_INDEXED("Hotovo"),
}

@Composable
fun IndexingQueueScreen(
    repository: JervisRepository,
    qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>>,
    onBack: () -> Unit,
) {
    var dashboard by remember { mutableStateOf<IndexingDashboardDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val kbPageSize = 20

    // KB Fronta: accumulated items for infinite scroll
    var kbWaitingItems by remember { mutableStateOf<List<PipelineItemDto>>(emptyList()) }
    var kbWaitingTotalCount by remember { mutableStateOf(0L) }
    var kbWaitingPage by remember { mutableStateOf(0) }
    var isLoadingMoreKb by remember { mutableStateOf(false) }

    // Hotovo: accumulated items for infinite scroll
    var indexedItems by remember { mutableStateOf<List<PipelineItemDto>>(emptyList()) }
    var indexedTotalCount by remember { mutableStateOf(0L) }
    var indexedPage by remember { mutableStateOf(0) }
    var isLoadingMoreIndexed by remember { mutableStateOf(false) }

    var expandedSection by remember { mutableStateOf(IndexingSection.KB_PROCESSING) }

    val scope = rememberCoroutineScope()

    fun loadDashboard(resetAccumulated: Boolean = true) {
        scope.launch {
            if (dashboard == null) isLoading = true
            error = null
            try {
                val data = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = 0,
                    kbPageSize = kbPageSize,
                )
                dashboard = data
                if (resetAccumulated) {
                    kbWaitingItems = data.kbWaiting
                    kbWaitingTotalCount = data.kbWaitingTotalCount
                    kbWaitingPage = 0
                    indexedItems = data.kbIndexed
                    indexedTotalCount = data.kbIndexedTotalCount
                    indexedPage = 0
                }
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
                println("IndexingQueueScreen loadDashboard error: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    fun loadMoreKbWaiting() {
        if (isLoadingMoreKb || kbWaitingItems.size.toLong() >= kbWaitingTotalCount) return
        scope.launch {
            isLoadingMoreKb = true
            try {
                val nextPage = kbWaitingPage + 1
                val more = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = nextPage,
                    kbPageSize = kbPageSize,
                )
                kbWaitingItems = kbWaitingItems + more.kbWaiting
                kbWaitingTotalCount = more.kbWaitingTotalCount
                kbWaitingPage = nextPage
            } catch (e: Exception) {
                println("IndexingQueueScreen loadMoreKbWaiting error: ${e.message}")
            } finally {
                isLoadingMoreKb = false
            }
        }
    }

    fun loadMoreIndexed() {
        if (isLoadingMoreIndexed || indexedItems.size.toLong() >= indexedTotalCount) return
        scope.launch {
            isLoadingMoreIndexed = true
            try {
                val nextPage = indexedPage + 1
                val more = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = nextPage,
                    kbPageSize = kbPageSize,
                )
                indexedItems = indexedItems + more.kbIndexed
                indexedTotalCount = more.kbIndexedTotalCount
                indexedPage = nextPage
            } catch (e: Exception) {
                println("IndexingQueueScreen loadMoreIndexed error: ${e.message}")
            } finally {
                isLoadingMoreIndexed = false
            }
        }
    }

    // Initial load
    LaunchedEffect(Unit) { loadDashboard() }

    // Auto-refresh every 10 seconds (safety net)
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            try {
                val data = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = 0,
                    kbPageSize = kbPageSize,
                )
                dashboard = data
                // Update processing and counts, but don't reset accumulated scroll positions
                kbWaitingTotalCount = data.kbWaitingTotalCount
                indexedTotalCount = data.kbIndexedTotalCount
            } catch (_: Exception) {}
        }
    }

    // Event-driven refresh: watch for task completion events
    val progress by qualificationProgress.collectAsState()
    LaunchedEffect(progress.size) {
        // When a task finishes (disappears from active progress), refresh dashboard
        if (dashboard != null && progress.isEmpty()) {
            delay(500) // Small debounce
            loadDashboard(resetAccumulated = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(title = "Fronta indexace")

        when {
            isLoading && dashboard == null -> JCenteredLoading()
            error != null && dashboard == null -> JErrorState(error!!, onRetry = { loadDashboard() })
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
                        IndexingSection.KB_PROCESSING -> {
                            KbProcessingSectionContent(
                                items = data.kbProcessing,
                                activeProgress = progress,
                            )
                        }
                        IndexingSection.KB_WAITING -> KbWaitingSectionContent(
                            items = kbWaitingItems,
                            totalCount = kbWaitingTotalCount,
                            isLoadingMore = isLoadingMoreKb,
                            onLoadMore = { loadMoreKbWaiting() },
                            repository = repository,
                            onRefresh = { loadDashboard() },
                        )
                        IndexingSection.KB_INDEXED -> IndexedSectionContent(
                            items = indexedItems,
                            totalCount = indexedTotalCount,
                            isLoadingMore = isLoadingMoreIndexed,
                            onLoadMore = { loadMoreIndexed() },
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
}

private fun sectionBadge(section: IndexingSection, data: IndexingDashboardDto): Int = when (section) {
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

// ── KB zpracování: 1 primary task with detailed progress timeline ──

@Composable
private fun KbProcessingSectionContent(
    items: List<PipelineItemDto>,
    activeProgress: Map<String, QualificationProgressInfo>,
) {
    if (items.isEmpty() && activeProgress.isEmpty()) {
        JEmptyState("Nic se nezpracovává", icon = "✓")
        return
    }

    // Ticker for relative timestamp updates — forces recomposition every 15s
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
    ) {
        // Primary task = first item with most progress detail
        val primary = items.firstOrNull()
        if (primary != null) {
            val progress = activeProgress[primary.taskId ?: primary.id]

            // Task header
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = primary.type.icon(),
                    contentDescription = primary.type.label(),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(JervisSpacing.itemGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primary.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = primary.connectionName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = primary.clientName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // CRITICAL badge
                if (primary.processingMode == "FOREGROUND") {
                    CriticalBadge()
                    Spacer(Modifier.width(4.dp))
                }
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Progress timeline
            if (progress != null && progress.steps.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    progress.steps.forEach { step ->
                        ProgressStepRow(
                            step = step,
                            isLatest = step == progress.steps.last(),
                            nowMs = nowMs,
                        )
                    }
                }
            } else {
                Text(
                    text = "Čeká na zpracování...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }

            // Error/retry info
            if (primary.retryCount > 0) {
                Text(
                    text = "Pokus ${primary.retryCount}" + (primary.nextRetryAt?.let { " · další ${formatNextCheck(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 28.dp, top = 4.dp),
                )
            }
            primary.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp, top = 2.dp),
                )
            }
        }

        // Other processing tasks indicator
        if (items.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "+${items.size - 1} další zpracovává",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
    }
}

@Composable
private fun ProgressStepRow(
    step: QualificationProgressStep,
    isLatest: Boolean,
    nowMs: Long,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // Step indicator dot
            val dotColor = if (isLatest) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))

            // Step content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLatest) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Relative timestamp
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatStepTimestamp(step.timestamp, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        // Structured metadata display for analysis and routing steps
        if (step.metadata.isNotEmpty() && step.step in listOf("analysis", "routing", "simple_action")) {
            Column(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                when (step.step) {
                    "analysis" -> {
                        MetadataRow("Chunks", step.metadata["chunksCount"])
                        MetadataRow("Entity", step.metadata["entities"])
                        MetadataRow("Actionable", step.metadata["actionable"]?.let { if (it == "true") "Ano" else "Ne" })
                        MetadataRow("Urgency", step.metadata["urgency"])
                        MetadataRow("Akce", step.metadata["suggestedActions"])
                        MetadataRow("Přiřazeno", step.metadata["isAssignedToMe"]?.let { if (it == "true") "Ano" else "Ne" })
                        step.metadata["suggestedDeadline"]?.takeIf { it.isNotBlank() }?.let { MetadataRow("Deadline", it) }
                    }
                    "routing" -> {
                        MetadataRow("Rozhodnutí", step.metadata["route"])
                        MetadataRow("Cílový stav", step.metadata["targetState"])
                    }
                    "simple_action" -> {
                        MetadataRow("Typ akce", step.metadata["actionType"])
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── KB Fronta: infinite scroll with reorder controls ──

@Composable
private fun KbWaitingSectionContent(
    items: List<PipelineItemDto>,
    totalCount: Long,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    repository: JervisRepository,
    onRefresh: () -> Unit,
) {
    if (items.isEmpty() && totalCount == 0L) {
        JEmptyState("Fronta je prázdná", icon = "✓")
        return
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Infinite scroll: load more when near end
    LaunchedEffect(listState, items.size) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5
        }.collect { nearEnd ->
            if (nearEnd && items.size.toLong() < totalCount) {
                onLoadMore()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            val item = items[index]
            KbWaitingItemRow(
                item = item,
                onPrioritize = {
                    item.taskId?.let { taskId ->
                        scope.launch {
                            try {
                                repository.indexingQueue.prioritizeKbQueueItem(taskId)
                                onRefresh()
                            } catch (_: Exception) {}
                        }
                    }
                },
                onProcessNow = {
                    item.taskId?.let { taskId ->
                        scope.launch {
                            try {
                                repository.indexingQueue.processKbItemNow(taskId)
                                onRefresh()
                            } catch (_: Exception) {}
                        }
                    }
                },
            )
        }

        if (isLoadingMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (items.size.toLong() < totalCount && !isLoadingMore) {
            item {
                Text(
                    text = "... a dalších ${totalCount - items.size} úloh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun KbWaitingItemRow(
    item: PipelineItemDto,
    onPrioritize: () -> Unit,
    onProcessNow: () -> Unit,
) {
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
        }

        // CRITICAL badge
        if (item.processingMode == "FOREGROUND") {
            CriticalBadge()
            Spacer(Modifier.width(4.dp))
        }

        // Priority badge
        item.queuePosition?.let { pos ->
            Text(
                text = "#$pos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(4.dp))
        }

        Text(
            text = item.type.label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// ── Hotovo: infinite scroll ──

@Composable
private fun IndexedSectionContent(
    items: List<PipelineItemDto>,
    totalCount: Long,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty()) {
        JEmptyState("Zatím nic neindexováno", icon = "✓")
        return
    }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, items.size) {
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex >= items.size - 5
        }.collect { nearEnd ->
            if (nearEnd && items.size.toLong() < totalCount) {
                onLoadMore()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            PipelineItemCompactRow(items[index])
        }
        if (isLoadingMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (items.size.toLong() < totalCount && !isLoadingMore) {
            item {
                Text(
                    text = "... a dalších ${totalCount - items.size} úloh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// ── Shared composables ──

@Composable
private fun CriticalBadge() {
    Text(
        text = "CRITICAL",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onError,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun PipelineItemCompactRow(item: PipelineItemDto) {
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
        }

        if (item.processingMode == "FOREGROUND") {
            CriticalBadge()
            Spacer(Modifier.width(4.dp))
        }

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
    "WAITING" -> "Čeká"
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

private fun formatStepTimestamp(epochMs: Long, nowMs: Long): String {
    val diff = nowMs - epochMs
    return when {
        diff < 1_000L -> "teď"
        diff < 60_000L -> "${diff / 1_000L}s"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        else -> "${diff / 3_600_000L}h"
    }
}
