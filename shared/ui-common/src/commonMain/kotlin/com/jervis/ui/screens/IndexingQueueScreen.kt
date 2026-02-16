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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
    var clientFilter by remember { mutableStateOf("") }
    var projectFilter by remember { mutableStateOf("") }
    var intervalDialog by remember { mutableStateOf<IntervalDialogState?>(null) }

    // Section expansion states
    var kbProcessingExpanded by remember { mutableStateOf(true) }
    var kbWaitingExpanded by remember { mutableStateOf(true) }
    var executionWaitingExpanded by remember { mutableStateOf(false) }
    var executionRunningExpanded by remember { mutableStateOf(false) }
    var kbIndexedExpanded by remember { mutableStateOf(false) }

    // KB pagination
    var kbPage by remember { mutableStateOf(0) }
    val kbPageSize = 20

    val scope = rememberCoroutineScope()

    fun loadDashboard() {
        scope.launch {
            isLoading = true
            error = null
            try {
                dashboard = repository.indexingQueue.getIndexingDashboard(
                    search = search,
                    kbPage = kbPage,
                    kbPageSize = kbPageSize,
                    clientFilter = clientFilter,
                    projectFilter = projectFilter,
                )
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

            // Client and Project filters
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JTextField(
                        value = clientFilter,
                        onValueChange = {
                            clientFilter = it
                            loadDashboard()
                        },
                        label = "Filtr klienta",
                        modifier = Modifier.weight(1f),
                    )
                    JTextField(
                        value = projectFilter,
                        onValueChange = {
                            projectFilter = it
                            loadDashboard()
                        },
                        label = "Filtr projektu",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Loading / error / content ──
            when {
                isLoading && dashboard == null -> item { JCenteredLoading() }
                error != null -> item { JErrorState(error!!, onRetry = { loadDashboard() }) }
                dashboard != null -> {
                    val data = dashboard!!

                    // ── Connection groups (hierarchical) ──
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
                                    val capGroup = group.capabilityGroups.find { it.capability == capability.name }
                                    intervalDialog = IntervalDialogState(
                                        capability = capability,
                                        currentIntervalMinutes = capGroup?.intervalMinutes ?: 30,
                                    )
                                },
                                onTriggerNow = { connectionId, capability ->
                                    scope.launch {
                                        try {
                                            repository.indexingQueue.triggerIndexNow(connectionId, capability)
                                            loadDashboard()
                                        } catch (_: Exception) {
                                            // ignore
                                        }
                                    }
                                },
                            )
                        }
                    }

                    // ── Pipeline sections ──
                    item { Spacer(Modifier.height(JervisSpacing.sectionGap)) }

                    // 1. KB Processing (QUALIFYING)
                    if (data.kbProcessingCount > 0) {
                        item {
                            PipelineSection(
                                title = "Zpracovává KB (${data.kbProcessingCount})",
                                items = data.kbProcessing,
                                expanded = kbProcessingExpanded,
                                onToggle = { kbProcessingExpanded = !kbProcessingExpanded },
                                accentColor = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }

                    // 2. KB Waiting (READY_FOR_QUALIFICATION) - with pagination + reorder + process now
                    item {
                        PipelineSection(
                            title = "Čeká na KB (${data.kbWaitingTotalCount})",
                            items = data.kbWaiting,
                            expanded = kbWaitingExpanded,
                            onToggle = { kbWaitingExpanded = !kbWaitingExpanded },
                            totalCount = data.kbWaitingTotalCount,
                            currentPage = data.kbPage,
                            pageSize = data.kbPageSize,
                            onPageChange = { newPage ->
                                kbPage = newPage
                                loadDashboard()
                            },
                            onPrioritize = { taskId ->
                                scope.launch {
                                    try {
                                        repository.indexingQueue.prioritizeKbQueueItem(taskId)
                                        loadDashboard()
                                    } catch (_: Exception) {
                                        // ignore
                                    }
                                }
                            },
                            onReorder = { taskId, newPosition ->
                                scope.launch {
                                    try {
                                        repository.indexingQueue.reorderKbQueueItem(taskId, newPosition)
                                        loadDashboard()
                                    } catch (_: Exception) {
                                        // ignore
                                    }
                                }
                            },
                            onProcessNow = { taskId ->
                                scope.launch {
                                    try {
                                        val success = repository.indexingQueue.processKbItemNow(taskId)
                                        if (success) {
                                            loadDashboard()
                                        } else {
                                            error = "Nelze zpracovat: jiná položka se již zpracovává"
                                        }
                                    } catch (e: Exception) {
                                        error = "Chyba: ${e.message}"
                                    }
                                }
                            },
                            showReorderControls = true,
                            showProcessNow = true,
                        )
                    }

                    // 3. Execution Waiting (READY_FOR_GPU)
                    if (data.executionWaitingCount > 0) {
                        item {
                            PipelineSection(
                                title = "Čeká na orchestrátor (${data.executionWaitingCount})",
                                items = data.executionWaiting,
                                expanded = executionWaitingExpanded,
                                onToggle = { executionWaitingExpanded = !executionWaitingExpanded },
                            )
                        }
                    }

                    // 4. Execution Running (DISPATCHED_GPU + PYTHON_ORCHESTRATING)
                    if (data.executionRunningCount > 0) {
                        item {
                            PipelineSection(
                                title = "Zpracovává orchestrátor (${data.executionRunningCount})",
                                items = data.executionRunning,
                                expanded = executionRunningExpanded,
                                onToggle = { executionRunningExpanded = !executionRunningExpanded },
                                accentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // 5. KB Indexed (INDEXED) - successfully inserted into KB
                    if (data.kbIndexedTotalCount > 0) {
                        item { Spacer(Modifier.height(JervisSpacing.sectionGap)) }
                        item {
                            PipelineSection(
                                title = "Úspěšně vloženo do KB (${data.kbIndexedTotalCount})",
                                items = data.kbIndexed,
                                expanded = kbIndexedExpanded,
                                onToggle = { kbIndexedExpanded = !kbIndexedExpanded },
                                accentColor = MaterialTheme.colorScheme.tertiary,
                            )
                        }
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
