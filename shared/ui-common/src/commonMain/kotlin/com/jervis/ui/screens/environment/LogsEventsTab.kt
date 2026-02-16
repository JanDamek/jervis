package com.jervis.ui.screens.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.K8sEventDto
import com.jervis.dto.environment.K8sPodDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * Logs & Events tab — pod log viewer + namespace-level K8s events.
 *
 * Pod Logs: dropdown to select a pod, monospace text area with log content, refresh + line count selector.
 * K8s Events: list of recent namespace events with type coloring (Warning = error color).
 */
@Composable
fun LogsEventsTab(
    environment: EnvironmentDto,
    repository: JervisRepository,
) {
    var pods by remember { mutableStateOf<List<K8sPodDto>>(emptyList()) }
    var selectedPod by remember { mutableStateOf<K8sPodDto?>(null) }
    var logContent by remember { mutableStateOf<String?>(null) }
    var logLoading by remember { mutableStateOf(false) }
    var tailLines by remember { mutableStateOf(100) }

    var events by remember { mutableStateOf<List<K8sEventDto>>(emptyList()) }
    var eventsLoading by remember { mutableStateOf(false) }

    var isLoadingPods by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadPods() {
        scope.launch {
            isLoadingPods = true
            try {
                val resources = repository.environmentResources.listResources(environment.id)
                pods = resources.pods
                // Auto-select first pod if none selected
                if (selectedPod == null && resources.pods.isNotEmpty()) {
                    selectedPod = resources.pods.first()
                }
            } catch (_: Exception) {
                pods = emptyList()
            } finally {
                isLoadingPods = false
            }
        }
    }

    fun loadLogs(podName: String) {
        scope.launch {
            logLoading = true
            logContent = null
            try {
                logContent = repository.environmentResources.getPodLogs(environment.id, podName, tailLines)
            } catch (e: Exception) {
                logContent = "Chyba: ${e.message}"
            } finally {
                logLoading = false
            }
        }
    }

    fun loadEvents() {
        scope.launch {
            eventsLoading = true
            try {
                val result = repository.environmentResources.getNamespaceEvents(environment.id, 50)
                events = result.events
            } catch (_: Exception) {
                events = emptyList()
            } finally {
                eventsLoading = false
            }
        }
    }

    // Load pods and events when environment changes
    LaunchedEffect(environment.id) {
        loadPods()
        loadEvents()
    }

    // Load logs when pod selection changes
    LaunchedEffect(selectedPod) {
        selectedPod?.let { loadLogs(it.name) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
    ) {
        // ── Pod Logs ────────────────────────────────────────────────────────────
        PodLogsSection(
            pods = pods,
            selectedPod = selectedPod,
            logContent = logContent,
            logLoading = logLoading,
            isLoadingPods = isLoadingPods,
            tailLines = tailLines,
            onPodSelected = { selectedPod = it },
            onTailLinesChanged = { tailLines = it },
            onRefresh = { selectedPod?.let { loadLogs(it.name) } },
        )

        // ── K8s Events ──────────────────────────────────────────────────────────
        EventsSection(
            events = events,
            eventsLoading = eventsLoading,
            onRefresh = { loadEvents() },
        )
    }
}

// ── Sub-components ──────────────────────────────────────────────────────────────

private val TAIL_LINE_OPTIONS = listOf(100, 200, 500)

@Composable
private fun PodLogsSection(
    pods: List<K8sPodDto>,
    selectedPod: K8sPodDto?,
    logContent: String?,
    logLoading: Boolean,
    isLoadingPods: Boolean,
    tailLines: Int,
    onPodSelected: (K8sPodDto) -> Unit,
    onTailLinesChanged: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    JSection(title = "Pod logy") {
        if (isLoadingPods) {
            JCenteredLoading()
        } else if (pods.isEmpty()) {
            JEmptyState(message = "Žádné pody k dispozici")
        } else {
            // Pod selector + line count + refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                JDropdown(
                    items = pods,
                    selectedItem = selectedPod,
                    onItemSelected = onPodSelected,
                    label = "Pod",
                    itemLabel = { it.name },
                    modifier = Modifier.weight(1f),
                )
                JDropdown(
                    items = TAIL_LINE_OPTIONS,
                    selectedItem = tailLines,
                    onItemSelected = onTailLinesChanged,
                    label = "Řádků",
                    itemLabel = { "$it" },
                )
                JRefreshButton(onClick = onRefresh)
            }

            Spacer(Modifier.height(JervisSpacing.itemGap))

            // Log content
            if (logLoading) {
                JCenteredLoading()
            } else if (logContent != null) {
                JCard {
                    Text(
                        text = logContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(JervisSpacing.sectionPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventsSection(
    events: List<K8sEventDto>,
    eventsLoading: Boolean,
    onRefresh: () -> Unit,
) {
    JSection(title = "K8s události") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            JRefreshButton(onClick = onRefresh)
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        if (eventsLoading) {
            JCenteredLoading()
        } else if (events.isEmpty()) {
            JEmptyState(message = "Žádné události")
        } else {
            LazyColumn(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(events) { event ->
                    EventRow(event)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: K8sEventDto) {
    val isWarning = event.type == "Warning"

    JCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.sectionPadding),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            // Type badge
            Text(
                text = event.type ?: "Normal",
                style = MaterialTheme.typography.labelSmall,
                color = if (isWarning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    event.reason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isWarning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    event.time?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                event.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
