package com.jervis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.PollingIntervalUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

private data class IntervalDialogState(
    val capability: ConnectionCapability,
    val currentIntervalMinutes: Int,
)

@Composable
fun IndexingQueueScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var dashboard by remember { mutableStateOf<IndexingDashboardDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var kbExpanded by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf<IntervalDialogState?>(null) }

    val scope = rememberCoroutineScope()

    fun loadDashboard() {
        scope.launch {
            isLoading = true
            error = null
            try {
                dashboard = repository.indexingQueue.getIndexingDashboard(search, 20)
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDashboard() }

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
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            // ── Action bar with search ──
            item {
                JActionBar {
                    JRefreshButton(onClick = { loadDashboard() })
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = "Hledat",
                        modifier = Modifier.weight(1f),
                    )
                    JIconButton(
                        onClick = { loadDashboard() },
                        icon = Icons.Default.Search,
                        contentDescription = "Hledat",
                    )
                }
            }

            // ── Loading / error / content ──
            when {
                isLoading && dashboard == null -> item { JCenteredLoading() }
                error != null -> item { JErrorState(error!!, onRetry = { loadDashboard() }) }
                dashboard != null -> {
                    val data = dashboard!!

                    // ── Connection groups ──
                    if (data.connectionGroups.isEmpty()) {
                        item {
                            JEmptyState("Žádné položky k indexaci", icon = "📋")
                        }
                    } else {
                        items(
                            items = data.connectionGroups,
                            key = { it.connectionId.ifEmpty { "git-${it.connectionName}" } },
                        ) { group ->
                            ConnectionGroupCard(
                                group = group,
                                onIntervalClick = { capability ->
                                    intervalDialog = IntervalDialogState(
                                        capability = capability,
                                        currentIntervalMinutes = group.intervalMinutes,
                                    )
                                },
                            )
                        }
                    }

                    // ── Spacer before KB section ──
                    item { Spacer(Modifier.height(JervisSpacing.sectionGap)) }

                    // ── KB Queue section (collapsed by default) ──
                    item {
                        KbQueueSection(
                            items = data.kbQueue,
                            totalCount = data.kbQueueTotalCount,
                            expanded = kbExpanded,
                            onToggle = { kbExpanded = !kbExpanded },
                        )
                    }
                }
            }

            // Bottom spacing
            item { Spacer(Modifier.height(JervisSpacing.sectionGap)) }
        }
    }

    // ── Polling interval dialog ──
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
                    } catch (e: Exception) {
                        // Dialog stays open on error – user can retry
                    }
                }
            },
            onDismiss = { intervalDialog = null },
        )
    }
}
