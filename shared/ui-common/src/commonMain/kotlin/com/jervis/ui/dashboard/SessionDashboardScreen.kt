package com.jervis.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.di.RpcConnectionManager
import com.jervis.dto.dashboard.ActiveSessionDto
import com.jervis.dto.dashboard.DashboardSnapshotDto
import com.jervis.dto.dashboard.EvictionRecordDto
import com.jervis.ui.design.JAdaptiveSidebarLayout
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JervisSpacing

/**
 * Read-only dashboard for the orchestrator's SessionBroker.
 *
 * Three sections (each is a sidebar category at expanded layout, full-screen
 * compact page at <600dp):
 *   1. "Aktivní sessions" — tabular list of running Claude sessions.
 *   2. "Vyhozeno (LRU)" — last 24 h of LRU evictions.
 *   3. "Tokeny" — aggregate cumulative token usage across active sessions.
 *
 * Push-only stream from `IDashboardService.subscribeSessionSnapshot` via
 * [RpcConnectionManager.resilientFlow] (rule #9). Pull-cadence inside the
 * Kotlin server is internal and does not break the UI contract.
 *
 * TODO (follow-up): historical per-day token chart needs persistent
 *   broker-side stats. Today only `cumulative_tokens` per running session
 *   is exposed; the "Tokeny" tab shows live aggregate only.
 */
@Composable
fun SessionDashboardScreen(
    connectionManager: RpcConnectionManager,
    onBack: (() -> Unit)? = null,
) {
    val viewModel = remember(connectionManager) { SessionDashboardViewModel(connectionManager) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    val snapshot by viewModel.snapshot.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    val tabs = remember(snapshot) {
        listOf(
            DashboardTab.ActiveSessions(snapshot?.activeCount ?: 0, snapshot?.cap ?: 0, snapshot?.paused ?: false),
            DashboardTab.Evictions(snapshot?.recentEvictions?.size ?: 0),
            DashboardTab.Tokens(snapshot?.sessions?.sumOf { it.cumulativeTokens } ?: 0),
        )
    }

    JAdaptiveSidebarLayout(
        categories = tabs,
        selectedIndex = selectedTab,
        onSelect = { selectedTab = it.coerceAtLeast(0).coerceAtMost(tabs.size - 1) },
        onBack = onBack,
        title = "Dashboard",
        categoryIcon = { tab ->
            Icon(
                imageVector = when (tab) {
                    is DashboardTab.ActiveSessions -> Icons.Outlined.Memory
                    is DashboardTab.Evictions -> Icons.Outlined.History
                    is DashboardTab.Tokens -> Icons.Outlined.Token
                },
                contentDescription = null,
            )
        },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
    ) { tab ->
        when (tab) {
            is DashboardTab.ActiveSessions -> ActiveSessionsPanel(snapshot)
            is DashboardTab.Evictions -> EvictionsPanel(snapshot)
            is DashboardTab.Tokens -> TokensPanel(snapshot)
        }
    }
}

private sealed interface DashboardTab {
    val title: String
    val description: String

    data class ActiveSessions(val active: Int, val cap: Int, val paused: Boolean) : DashboardTab {
        override val title: String = if (cap > 0) "Aktivní sessions ($active/$cap)" else "Aktivní sessions"
        override val description: String =
            if (paused) "Pause: ano (rate-limit back-pressure)" else "Pause: ne"
    }

    data class Evictions(val count: Int) : DashboardTab {
        override val title: String = "Vyhozeno (LRU)"
        override val description: String = "Posledních 24 h ($count)"
    }

    data class Tokens(val total: Int) : DashboardTab {
        override val title: String = "Tokeny"
        override val description: String = "Souhrn napříč běžícími sessions ($total)"
    }
}

@Composable
private fun ActiveSessionsPanel(snapshot: DashboardSnapshotDto?) {
    if (snapshot == null) {
        JCenteredLoading()
        return
    }
    val sessions = snapshot.sessions
    if (sessions.isEmpty()) {
        JEmptyState(
            message = "Žádná aktivní session.",
            icon = Icons.Outlined.Insights,
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (snapshot.paused) {
            PausedBanner()
            Spacer(Modifier.height(JervisSpacing.itemGap))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            items(sessions, key = { it.scope }) { session ->
                ActiveSessionRow(
                    session = session,
                    holdingAgentJobs = snapshot.agentJobHolds.entries
                        .filter { it.value == session.scope }
                        .map { it.key },
                )
            }
        }
    }
}

@Composable
private fun PausedBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = "Broker je pozastaven (rate-limit). Nové sessions se nedrainují, dokud Claude API nepřijme další požadavky.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(JervisSpacing.sectionPadding),
        )
    }
}

@Composable
private fun ActiveSessionRow(session: ActiveSessionDto, holdingAgentJobs: List<String>) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = session.scope,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "session_id=${session.sessionId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.compactInProgress) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Souhrn pamětí v běhu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(JervisSpacing.itemGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MetricCell(label = "Tokeny celkem", value = session.cumulativeTokens.toString())
            MetricCell(label = "Idle", value = formatSeconds(session.idleSeconds))
            MetricCell(
                label = "Poslední souhrn",
                value = if (session.lastCompactAgeSeconds > 0) formatSeconds(session.lastCompactAgeSeconds) else "—",
            )
        }
        if (holdingAgentJobs.isNotEmpty()) {
            Spacer(Modifier.height(JervisSpacing.itemGap))
            Text(
                text = "Drží agent jobs: ${holdingAgentJobs.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EvictionsPanel(snapshot: DashboardSnapshotDto?) {
    if (snapshot == null) {
        JCenteredLoading()
        return
    }
    val evictions = snapshot.recentEvictions
    if (evictions.isEmpty()) {
        JEmptyState(
            message = "Za posledních 24 h žádné vyhozené sessions.",
            icon = Icons.Outlined.History,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
    ) {
        items(evictions, key = { "${it.scope}-${it.ts}" }) { rec ->
            EvictionRow(rec)
        }
    }
}

@Composable
private fun EvictionRow(rec: EvictionRecordDto) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = rec.scope.ifBlank { "(neznámý scope)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Důvod: ${rec.reason.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = rec.ts,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokensPanel(snapshot: DashboardSnapshotDto?) {
    if (snapshot == null) {
        JCenteredLoading()
        return
    }
    val total = snapshot.sessions.sumOf { it.cumulativeTokens }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
    ) {
        JCard {
            Text(
                text = "Tokeny celkem (běžící sessions)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(JervisSpacing.itemGap))
            Text(
                text = "Per-session aktuální token usage. Historický graf per-day se připravuje — vyžaduje persistentní broker stats (TODO).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.sessions.isEmpty()) {
            JEmptyState(
                message = "Žádná běžící session.",
                icon = Icons.Outlined.Token,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                items(snapshot.sessions, key = { it.scope }) { session ->
                    JCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = session.scope,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = session.cumulativeTokens.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
