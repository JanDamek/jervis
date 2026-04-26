package com.jervis.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.dto.agentjob.AgentJobListSnapshot
import com.jervis.dto.agentjob.AgentJobSnapshot
import com.jervis.dto.vnc.VncSessionSnapshot

/**
 * Sidebar Background section — global view of background coding agents
 * + active VNC sessions.
 *
 * Brief Fáze K layout:
 * ```
 * Background  (3 running, 2 recent, 1 VNC)
 * ├── Running (3)
 * │   ├── 🟢 Title — client A — 4 min
 * │   ├── 🟡 Title — Jervis — 12 min  (waiting user)
 * │   └── 🟢 Title — client B — 1 min
 * ├── Queued (1)
 * │   └── ⚪ Title — client A — pending
 * ├── Recent (2 — last 24h)
 * │   ├── ✅ Title — Jervis — 2h ago — DONE
 * │   └── ❌ Title — Jervis — 3h ago — ERROR
 * └── VNC sessions (1 active)
 *     └── 🖥️ damek@outlook.com → Outlook Web — open
 * ```
 *
 * Click on an agent row → onAgentJobSelected callback (host opens detail).
 * Click on a VNC row → onVncSessionSelected callback.
 */
@Composable
fun BackgroundSection(
    snapshot: AgentJobListSnapshot?,
    vncSessions: List<VncSessionSnapshot>,
    onAgentJobSelected: (AgentJobSnapshot) -> Unit,
    onVncSessionEmbed: (VncSessionSnapshot) -> Unit,
    onVncSessionExternal: (VncSessionSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    var showRunning by remember { mutableStateOf(true) }
    var showQueued by remember { mutableStateOf(true) }
    var showRecent by remember { mutableStateOf(false) }
    var showVnc by remember { mutableStateOf(true) }

    val running = snapshot?.running.orEmpty()
    val queued = snapshot?.queued.orEmpty()
    val waitingUser = snapshot?.waitingUser.orEmpty()
    val recent = snapshot?.recent.orEmpty()

    val runningCount = running.size + waitingUser.size
    val queuedCount = queued.size
    val recentCount = recent.size
    val vncCount = vncSessions.size

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Background  ($runningCount running, $queuedCount queued, $recentCount recent, $vncCount VNC)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (!expanded) return@Column

            if (runningCount > 0) {
                SubGroupHeader("Running", runningCount, showRunning) { showRunning = !showRunning }
                if (showRunning) {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(running, key = { it.id }) { row ->
                            AgentJobRow(row, onClick = { onAgentJobSelected(row) })
                        }
                        items(waitingUser, key = { it.id }) { row ->
                            AgentJobRow(row, onClick = { onAgentJobSelected(row) })
                        }
                    }
                }
            }
            if (queuedCount > 0) {
                SubGroupHeader("Queued", queuedCount, showQueued) { showQueued = !showQueued }
                if (showQueued) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(queued, key = { it.id }) { row ->
                            AgentJobRow(row, onClick = { onAgentJobSelected(row) })
                        }
                    }
                }
            }
            if (recentCount > 0) {
                SubGroupHeader("Recent (24h)", recentCount, showRecent) { showRecent = !showRecent }
                if (showRecent) {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(recent, key = { it.id }) { row ->
                            AgentJobRow(row, onClick = { onAgentJobSelected(row) })
                        }
                    }
                }
            }
            if (vncCount > 0) {
                SubGroupHeader("VNC sessions", vncCount, showVnc) { showVnc = !showVnc }
                if (showVnc) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(vncSessions, key = { it.podName }) { session ->
                            VncSessionRow(
                                session = session,
                                onClickRow = { onVncSessionEmbed(session) },
                                onClickExternal = { onVncSessionExternal(session) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubGroupHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun AgentJobRow(snapshot: AgentJobSnapshot, onClick: () -> Unit) {
    val (badge, badgeColor) = when (snapshot.state) {
        "RUNNING" -> "🟢" to MaterialTheme.colorScheme.primary
        "QUEUED" -> "⚪" to MaterialTheme.colorScheme.onSurfaceVariant
        "WAITING_USER" -> "🟡" to MaterialTheme.colorScheme.tertiary
        "DONE" -> "✅" to MaterialTheme.colorScheme.primary
        "ERROR" -> "❌" to MaterialTheme.colorScheme.error
        "CANCELLED" -> "🚫" to MaterialTheme.colorScheme.outline
        else -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(badge, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = snapshot.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val sub = buildString {
                append(snapshot.flavor.lowercase())
                snapshot.gitBranch?.let { append(" • ").append(it) }
                snapshot.gitCommitSha?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it.take(7)) }
                snapshot.errorMessage?.takeIf { it.isNotBlank() && snapshot.state == "ERROR" }
                    ?.let { append(" • ").append(it.take(60)) }
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VncSessionRow(
    session: VncSessionSnapshot,
    onClickRow: () -> Unit,
    onClickExternal: () -> Unit,
) {
    val placeholder = !session.requiresMint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (placeholder) Modifier
                else Modifier.clickable(onClick = onClickRow),
            )
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (placeholder) "🚧" else "🖥️", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.connectionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = session.note ?: session.podName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (!placeholder) {
            // Secondary action: open the VNC URL in the user's default
            // browser instead of embedding. Useful for mobile / share /
            // multi-monitor flows. Same `getBrowserSessionStatus` mint
            // path either way.
            androidx.compose.material3.IconButton(
                onClick = onClickExternal,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Otevřít v prohlížeči",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
