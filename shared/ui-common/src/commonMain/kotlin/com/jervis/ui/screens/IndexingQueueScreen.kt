package com.jervis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

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
