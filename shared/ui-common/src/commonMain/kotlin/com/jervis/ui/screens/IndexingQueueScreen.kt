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
import com.jervis.dto.indexing.QualificationStepDto
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
                // Update counts
                kbWaitingTotalCount = data.kbWaitingTotalCount
                indexedTotalCount = data.kbIndexedTotalCount
                // Always refresh Hotovo page 0 so newly completed items appear
                indexedItems = data.kbIndexed
                indexedPage = 0
            } catch (_: Exception) {}
        }
    }

    // Event-driven refresh: watch for qualification progress changes
    val progress by qualificationProgress.collectAsState()
    var lastProgressKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(progress.keys.toSet()) {
        val currentKeys = progress.keys.toSet()
        val newTasks = currentKeys - lastProgressKeys
        val finishedTasks = lastProgressKeys - currentKeys
        lastProgressKeys = currentKeys

        // Refresh dashboard when a task starts or finishes qualifying
        if (dashboard != null && (newTasks.isNotEmpty() || finishedTasks.isNotEmpty())) {
            delay(300)
            loadDashboard(resetAccumulated = false)
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

    // Ticker for elapsed time + relative timestamp updates — every 1s
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
    ) {
        // Primary task = first item with most progress detail
        val primary = items.firstOrNull()
        if (primary != null) {
            val lookupKey = primary.taskId ?: primary.id
            // Try exact match first, then fallback to any active progress (concurrency=1)
            val progress = activeProgress[lookupKey] ?: activeProgress.values.firstOrNull()
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
                // Elapsed time since active processing started (not queue time)
                val startMs = primary.qualificationStartedAt?.let {
                    try { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() } catch (_: Exception) { null }
                } ?: progress?.steps?.firstOrNull()?.timestamp
                if (startMs != null) {
                    Text(
                        text = formatElapsedTime(startMs, nowMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Progress timeline — merge stored DB steps + live events, deduplicate
            val liveSteps = progress?.steps ?: emptyList()
            val storedSteps = primary.qualificationSteps.map { dto ->
                QualificationProgressStep(
                    timestamp = try { kotlinx.datetime.Instant.parse(dto.timestamp).toEpochMilliseconds() } catch (_: Exception) { 0L },
                    message = dto.message,
                    step = dto.step,
                    metadata = dto.metadata,
                )
            }
            val lastStoredTs = storedSteps.lastOrNull()?.timestamp ?: 0L
            // Only include live steps at least 1s newer (prevents duplicates from near-simultaneous DB save + emit)
            val newerLiveSteps = liveSteps.filter { it.timestamp > lastStoredTs + 1000 }
            val displaySteps = (storedSteps + newerLiveSteps).distinctBy { it.step }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (displaySteps.isNotEmpty()) {
                    // Current step on top, completed steps below (newest first)
                    val reversed = displaySteps.reversed()
                    reversed.forEachIndexed { index, step ->
                        // Duration: latest step = still running, others = time until next step
                        val durationMs = if (index == 0) {
                            nowMs - step.timestamp
                        } else {
                            reversed[index - 1].timestamp - step.timestamp
                        }
                        ProgressStepRow(
                            step = step,
                            isLatest = index == 0,
                            durationMs = durationMs.coerceAtLeast(0),
                        )
                    }
                } else {
                    // No steps at all — show pipeline state as fallback
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pipelineStateLabel(primary.pipelineState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
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
    durationMs: Long,
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

            // Step duration (how long this step took)
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        // Structured metadata display for key steps
        val metadataSteps = listOf("summary_done", "rag_done", "llm_done", "content_ready", "hash_match", "routing", "simple_action")
        if (step.metadata.isNotEmpty() && step.step in metadataSteps) {
            Column(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                when (step.step) {
                    "content_ready" -> {
                        MetadataRow("Délka", step.metadata["content_length"]?.let { "$it znaků" })
                    }
                    "rag_done" -> {
                        MetadataRow("Chunks", step.metadata["chunks"])
                    }
                    "llm_done" -> {
                        MetadataRow("Entity", step.metadata["entities"])
                        MetadataRow("Akční", step.metadata["actionable"]?.let { if (it == "true") "Ano" else "Ne" })
                    }
                    "hash_match" -> {
                        MetadataRow("Chunks", step.metadata["chunks"]?.let { "$it (nezměněno)" })
                    }
                    "summary_done" -> {
                        MetadataRow("Entity", step.metadata["entities"])
                        MetadataRow("Akční", step.metadata["actionable"]?.let { if (it == "true") "Ano" else "Ne" })
                        MetadataRow("Urgence", step.metadata["urgency"])
                        MetadataRow("Akce", step.metadata["suggestedActions"])
                        MetadataRow("Přiřazeno", step.metadata["assignedTo"]?.takeIf { it.isNotBlank() })
                        step.metadata["suggestedDeadline"]?.takeIf { it.isNotBlank() }?.let { MetadataRow("Deadline", it) }
                    }
                    "routing" -> {
                        MetadataRow("Důvod", step.metadata["route"])
                        MetadataRow("Výsledek", step.metadata["result"])
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

    // Track which items are expanded to show history
    var expandedId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items.size, key = { items[it].id }) { index ->
            val item = items[index]
            val isExpanded = expandedId == item.id
            Column {
                PipelineItemCompactRow(
                    item = item,
                    onClick = if (item.qualificationSteps.isNotEmpty()) {
                        { expandedId = if (isExpanded) null else item.id }
                    } else null,
                )
                // Expandable qualification history
                if (isExpanded && item.qualificationSteps.isNotEmpty()) {
                    QualificationHistoryPanel(item.qualificationSteps, item.qualificationStartedAt)
                }
            }
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
private fun PipelineItemCompactRow(item: PipelineItemDto, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
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

        // Show qualification duration if available
        item.qualificationStartedAt?.let { startIso ->
            val startMs = try { kotlinx.datetime.Instant.parse(startIso).toEpochMilliseconds() } catch (_: Exception) { null }
            val endMs = item.qualificationSteps.lastOrNull()?.let { step ->
                try { kotlinx.datetime.Instant.parse(step.timestamp).toEpochMilliseconds() } catch (_: Exception) { null }
            }
            if (startMs != null && endMs != null) {
                Text(
                    text = formatElapsedTime(startMs, endMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
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

        // Expand arrow if history available
        if (item.qualificationSteps.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Zobrazit historii",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Expandable panel showing stored qualification history for completed items.
 */
@Composable
private fun QualificationHistoryPanel(
    steps: List<QualificationStepDto>,
    qualificationStartedAt: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Show total qualification time header
        if (qualificationStartedAt != null && steps.isNotEmpty()) {
            val startMs = try { kotlinx.datetime.Instant.parse(qualificationStartedAt).toEpochMilliseconds() } catch (_: Exception) { null }
            val endMs = steps.last().let { step ->
                try { kotlinx.datetime.Instant.parse(step.timestamp).toEpochMilliseconds() } catch (_: Exception) { null }
            }
            if (startMs != null && endMs != null) {
                Text(
                    text = "Kvalifikace: ${formatElapsedTime(startMs, endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        steps.forEachIndexed { index, step ->
            // Compute step duration: time until next step (or end of qualification)
            val stepDurationMs = if (index < steps.size - 1) {
                val nextTs = try { kotlinx.datetime.Instant.parse(steps[index + 1].timestamp).toEpochMilliseconds() } catch (_: Exception) { 0L }
                val thisTs = try { kotlinx.datetime.Instant.parse(step.timestamp).toEpochMilliseconds() } catch (_: Exception) { 0L }
                (nextTs - thisTs).coerceAtLeast(0)
            } else {
                0L // Last step — no meaningful duration
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                val isTerminal = step.step in listOf("done", "routing")
                val dotColor = if (isTerminal) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.outlineVariant
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(dotColor),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isTerminal) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Show key metadata inline
                    val inlineMeta = buildHistoryMetadata(step)
                    if (inlineMeta.isNotEmpty()) {
                        Text(
                            text = inlineMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Step duration
                if (stepDurationMs > 0) {
                    Text(
                        text = formatDuration(stepDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

/**
 * Build a concise metadata summary string for a history step.
 */
private fun buildHistoryMetadata(step: QualificationStepDto): String {
    val meta = step.metadata
    return when (step.step) {
        "content_ready" -> listOfNotNull(
            meta["content_length"]?.let { "${it} znaků" },
        ).joinToString(" · ")
        "rag_done" -> listOfNotNull(
            meta["chunks"]?.let { "$it chunks" },
        ).joinToString(" · ")
        "hash_match" -> listOfNotNull(
            meta["chunks"]?.let { "$it chunks (nezměněno)" },
        ).joinToString(" · ")
        "summary_done" -> listOfNotNull(
            meta["entities"]?.takeIf { it.isNotBlank() }?.let { "Entity: $it" },
            meta["actionable"]?.let { if (it == "true") "Actionable" else null },
            meta["urgency"]?.takeIf { it != "normal" }?.let { "Urgence: $it" },
            meta["suggestedActions"]?.takeIf { it.isNotBlank() }?.let { "Akce: $it" },
        ).joinToString(" · ")
        "routing" -> listOfNotNull(
            meta["route"]?.let { "→ $it" },
            meta["result"],
        ).joinToString(" · ")
        "simple_action" -> listOfNotNull(
            meta["actionType"],
        ).joinToString(" · ")
        else -> ""
    }
}

@Composable
private fun pipelineStateLabel(state: String): String = when (state) {
    "WAITING" -> "Čeká"
    "QUALIFYING" -> "Kvalifikuje"
    "RETRYING" -> "Opakuje"
    "INDEXED" -> "Hotovo"
    else -> state
}

@Composable
private fun pipelineStateColor(state: String): androidx.compose.ui.graphics.Color = when (state) {
    "WAITING" -> MaterialTheme.colorScheme.onSurfaceVariant
    "QUALIFYING" -> MaterialTheme.colorScheme.tertiary
    "RETRYING" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    return when {
        seconds < 1 -> "<1s"
        minutes < 1 -> "${seconds}s"
        else -> "${minutes}m ${seconds % 60}s"
    }
}

private fun formatElapsedTime(startMs: Long, nowMs: Long): String {
    val diff = (nowMs - startMs).coerceAtLeast(0)
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
