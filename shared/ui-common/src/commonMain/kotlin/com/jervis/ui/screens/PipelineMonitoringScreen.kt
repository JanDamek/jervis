package com.jervis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.PipelineItemDto
import com.jervis.di.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EPIC 2-S7: Pipeline Monitoring Dashboard.
 *
 * Displays the funnel view of the content processing pipeline:
 * Polled → Qualifying → KB Processing → Execution → Done.
 *
 * Shows counts per stage, recent items, and processing statistics.
 * Auto-refreshes every 15 seconds.
 */
@Composable
fun PipelineMonitoringScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var dashboard by remember { mutableStateOf<IndexingDashboardDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadDashboard() {
        scope.launch {
            isLoading = dashboard == null
            error = null
            try {
                dashboard = repository.indexingQueue.getIndexingDashboard(
                    search = "",
                    kbPage = 0,
                    kbPageSize = 20,
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Chyba při načítání pipeline: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Initial load + auto-refresh every 15s
    LaunchedEffect(Unit) {
        while (true) {
            loadDashboard()
            delay(15_000)
        }
    }

    Scaffold(
        topBar = {
            JTopBar(title = "Pipeline Monitor", onBack = onBack)
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(JervisSpacing.outerPadding),
        ) {
            when {
                isLoading && dashboard == null -> JCenteredLoading()
                error != null && dashboard == null -> JErrorState(message = error!!, onRetry = { loadDashboard() })
                dashboard != null -> PipelineDashboardContent(dashboard!!)
                else -> JEmptyState(message = "Žádná data pipeline")
            }
        }
    }
}

@Composable
private fun PipelineDashboardContent(dashboard: IndexingDashboardDto) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
    ) {
        // Funnel overview
        item {
            Text("Funnel", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }

        item {
            FunnelCard(dashboard)
        }

        // KB Processing stage
        item {
            Spacer(Modifier.height(16.dp))
            StageHeader("KB Zpracování", dashboard.kbProcessingCount.toInt())
        }

        items(dashboard.kbProcessing.take(5)) { item ->
            PipelineItemCard(item)
        }

        // KB Waiting stage
        item {
            StageHeader("KB Čeká", dashboard.kbWaitingTotalCount.toInt())
        }

        items(dashboard.kbWaiting.take(5)) { item ->
            PipelineItemCard(item)
        }

        // Execution stage
        item {
            StageHeader("Provádění", dashboard.executionRunningCount.toInt())
        }

        items(dashboard.executionRunning.take(5)) { item ->
            PipelineItemCard(item)
        }

        // Execution Waiting
        item {
            StageHeader("Čeká na provedení", dashboard.executionWaitingCount.toInt())
        }

        items(dashboard.executionWaiting.take(5)) { item ->
            PipelineItemCard(item)
        }

        // Recently completed
        item {
            StageHeader("Dokončeno", dashboard.kbIndexedTotalCount.toInt())
        }

        items(dashboard.kbIndexed.take(5)) { item ->
            PipelineItemCard(item)
        }
    }
}

@Composable
private fun FunnelCard(dashboard: IndexingDashboardDto) {
    JCard {
        Column(modifier = Modifier.padding(JervisSpacing.sectionPadding)) {
            JKeyValueRow("KB čeká", "${dashboard.kbWaitingTotalCount}")
            JKeyValueRow("KB zpracování", "${dashboard.kbProcessingCount}")
            JKeyValueRow("Čeká na provedení", "${dashboard.executionWaitingCount}")
            JKeyValueRow("Probíhá", "${dashboard.executionRunningCount}")
            JKeyValueRow("Hotovo", "${dashboard.kbIndexedTotalCount}")
            dashboard.kbQueueStats?.let { stats ->
                Spacer(Modifier.height(8.dp))
                Text("Statistiky KB fronty", style = MaterialTheme.typography.labelLarge)
                JKeyValueRow("Celkem", "${stats.total}")
                JKeyValueRow("Čeká", "${stats.pending}")
                JKeyValueRow("Zpracovává se", "${stats.inProgress}")
                JKeyValueRow("Selhalo", "${stats.failed}")
            }
        }
    }
}

@Composable
private fun StageHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PipelineItemCard(item: PipelineItemDto) {
    JCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.title.take(60),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                JStatusBadge(status = item.pipelineState)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${item.connectionName} | ${item.clientName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.errorMessage!!.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
