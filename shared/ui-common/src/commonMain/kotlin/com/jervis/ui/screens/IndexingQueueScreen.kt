package com.jervis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.getCapabilityLabel
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

@Composable
fun IndexingQueueScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    // Connections section state
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var connectionsLoading by remember { mutableStateOf(false) }
    var connectionsError by remember { mutableStateOf<String?>(null) }
    var connectionsSearch by remember { mutableStateOf("") }

    // Pending items section state
    var pendingPage by remember { mutableStateOf<IndexingQueuePageDto?>(null) }
    var pendingLoading by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var pendingSearch by remember { mutableStateOf("") }
    var pendingPageNum by remember { mutableIntStateOf(0) }

    // Indexed items section state
    var indexedPage by remember { mutableStateOf<IndexingQueuePageDto?>(null) }
    var indexedLoading by remember { mutableStateOf(false) }
    var indexedError by remember { mutableStateOf<String?>(null) }
    var indexedSearch by remember { mutableStateOf("") }
    var indexedPageNum by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    fun loadConnections() {
        scope.launch {
            connectionsLoading = true
            connectionsError = null
            try {
                connections = repository.connections.getAllConnections()
            } catch (e: Exception) {
                connectionsError = "Chyba: ${e.message}"
            } finally {
                connectionsLoading = false
            }
        }
    }

    fun loadPending() {
        scope.launch {
            pendingLoading = true
            pendingError = null
            try {
                pendingPage = repository.indexingQueue.getPendingItems(pendingPageNum, PAGE_SIZE, pendingSearch)
            } catch (e: Exception) {
                pendingError = "Chyba: ${e.message}"
            } finally {
                pendingLoading = false
            }
        }
    }

    fun loadIndexed() {
        scope.launch {
            indexedLoading = true
            indexedError = null
            try {
                indexedPage = repository.indexingQueue.getIndexedItems(indexedPageNum, PAGE_SIZE, indexedSearch)
            } catch (e: Exception) {
                indexedError = "Chyba: ${e.message}"
            } finally {
                indexedLoading = false
            }
        }
    }

    fun loadAll() {
        loadConnections()
        loadPending()
        loadIndexed()
    }

    LaunchedEffect(Unit) { loadAll() }
    LaunchedEffect(pendingPageNum) { loadPending() }
    LaunchedEffect(indexedPageNum) { loadIndexed() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Fronta indexace",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = JervisSpacing.outerPadding),
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
        ) {
            // ── Refresh bar ──
            item {
                JActionBar {
                    JRefreshButton(onClick = { loadAll() })
                }
            }

            // ── Section 1: Polling connections ──
            item {
                ConnectionsSection(
                    connections = connections,
                    isLoading = connectionsLoading,
                    error = connectionsError,
                    search = connectionsSearch,
                    onSearchChange = { connectionsSearch = it },
                    onRetry = { loadConnections() },
                )
            }

            // ── Section 2: Pending items ──
            item {
                QueueItemsSection(
                    title = "Čeká na indexaci",
                    page = pendingPage,
                    isLoading = pendingLoading,
                    error = pendingError,
                    search = pendingSearch,
                    onSearchChange = { pendingSearch = it },
                    onSearch = {
                        pendingPageNum = 0
                        loadPending()
                    },
                    pageNum = pendingPageNum,
                    onPrevPage = { if (pendingPageNum > 0) pendingPageNum-- },
                    onNextPage = {
                        val totalPages = pendingPage?.let {
                            ((it.totalCount + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
                        } ?: 1
                        if (pendingPageNum < totalPages - 1) pendingPageNum++
                    },
                    onRetry = { loadPending() },
                )
            }

            // ── Section 3: Indexed items ──
            item {
                QueueItemsSection(
                    title = "Odesláno do KB",
                    page = indexedPage,
                    isLoading = indexedLoading,
                    error = indexedError,
                    search = indexedSearch,
                    onSearchChange = { indexedSearch = it },
                    onSearch = {
                        indexedPageNum = 0
                        loadIndexed()
                    },
                    pageNum = indexedPageNum,
                    onPrevPage = { if (indexedPageNum > 0) indexedPageNum-- },
                    onNextPage = {
                        val totalPages = indexedPage?.let {
                            ((it.totalCount + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
                        } ?: 1
                        if (indexedPageNum < totalPages - 1) indexedPageNum++
                    },
                    onRetry = { loadIndexed() },
                )
            }

            // Bottom spacing
            item { Spacer(Modifier.height(JervisSpacing.sectionGap)) }
        }
    }
}

// ── Section 1: Connections ──────────────────────────────────────────────────────

@Composable
private fun ConnectionsSection(
    connections: List<ConnectionResponseDto>,
    isLoading: Boolean,
    error: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val filtered = if (search.isBlank()) {
        connections
    } else {
        val q = search.trim().lowercase()
        connections.filter { conn ->
            conn.name.lowercase().contains(q) ||
                conn.provider.name.lowercase().contains(q) ||
                conn.capabilities.any { getCapabilityLabel(it).lowercase().contains(q) }
        }
    }

    JSection(title = "Připojení") {
        JTextField(
            value = search,
            onValueChange = onSearchChange,
            label = "Hledat připojení",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(JervisSpacing.itemGap))

        when {
            isLoading && connections.isEmpty() -> JCenteredLoading()
            error != null -> JErrorState(error, onRetry = onRetry)
            filtered.isEmpty() -> JEmptyState("Žádná připojení", icon = "🔌")
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap)) {
                    for (conn in filtered) {
                        ConnectionCard(conn)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionResponseDto) {
    JCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(JervisSpacing.sectionPadding)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${connection.provider.name} · ${connection.state.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (connection.capabilities.isNotEmpty()) {
                    Text(
                        text = connection.capabilities.joinToString(", ") { getCapabilityLabel(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Section 2 & 3: Queue items ──────────────────────────────────────────────────

@Composable
private fun QueueItemsSection(
    title: String,
    page: IndexingQueuePageDto?,
    isLoading: Boolean,
    error: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    pageNum: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
) {
    val totalPages = page?.let {
        ((it.totalCount + PAGE_SIZE - 1) / PAGE_SIZE).toInt().coerceAtLeast(1)
    } ?: 1

    JSection(title = "$title (${page?.totalCount ?: 0})") {
        // Search row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            JTextField(
                value = search,
                onValueChange = onSearchChange,
                label = "Hledat",
                modifier = Modifier.weight(1f),
            )
            JIconButton(
                onClick = onSearch,
                icon = Icons.Default.Search,
                contentDescription = "Hledat",
            )
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        when {
            isLoading && page == null -> JCenteredLoading()
            error != null -> JErrorState(error, onRetry = onRetry)
            page == null || page.items.isEmpty() -> JEmptyState("Žádné položky", icon = "📋")
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap)) {
                    for (item in page.items) {
                        QueueItemCard(item)
                    }
                }

                Spacer(Modifier.height(JervisSpacing.itemGap))

                // Pagination
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JIconButton(
                        onClick = onPrevPage,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Předchozí",
                        enabled = pageNum > 0,
                    )
                    Spacer(Modifier.width(JervisSpacing.itemGap))
                    Text(
                        text = "Strana ${pageNum + 1} / $totalPages",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(JervisSpacing.itemGap))
                    JIconButton(
                        onClick = onNextPage,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Další",
                        enabled = pageNum < totalPages - 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueItemCard(item: IndexingQueueItemDto) {
    JCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(JervisSpacing.sectionPadding)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Type icon
            Icon(
                imageVector = item.type.icon(),
                contentDescription = item.type.label(),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.width(JervisSpacing.itemGap))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.connectionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = item.clientName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.projectName?.let { projectName ->
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = projectName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

private fun IndexingItemType.icon(): ImageVector = when (this) {
    IndexingItemType.GIT_COMMIT -> Icons.Default.Code
    IndexingItemType.EMAIL -> Icons.Default.Email
    IndexingItemType.BUGTRACKER_ISSUE -> Icons.Default.BugReport
    IndexingItemType.WIKI_PAGE -> Icons.Default.Description
}

private fun IndexingItemType.label(): String = when (this) {
    IndexingItemType.GIT_COMMIT -> "Git commit"
    IndexingItemType.EMAIL -> "Email"
    IndexingItemType.BUGTRACKER_ISSUE -> "Issue"
    IndexingItemType.WIKI_PAGE -> "Wiki"
}
