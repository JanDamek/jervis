package com.jervis.ui.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.dto.agentjob.AgentJobSnapshot
import com.jervis.dto.agentjob.AgentNarrativeEvent

/**
 * Detail panel for one expanded agent-job — renders the structured
 * Claude CLI narrative plus the job's own metadata (state, branch,
 * commit, error). Empty narrative → placeholder text.
 *
 * Brief Fáze K layout (terminal job):
 * ```
 * ┌─ Title ─────────────── ✅ DONE — 4 min ──┐
 * │ Branch: feature/...                      │
 * │ Commit: a1b2c3d                          │
 * │ Summary: ...                             │
 * │                                          │
 * │  💬  I'll start by exploring …           │
 * │  🔧  Read backend/...                    │
 * │  ✓   Read 472 lines                      │
 * │  ...                                     │
 * └──────────────────────────────────────────┘
 * ```
 */
@Composable
fun AgentNarrativeDetailPanel(
    snapshot: AgentJobSnapshot,
    narrative: List<AgentNarrativeEvent>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${snapshot.state} • ${snapshot.flavor.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            MetadataLine("Branch", snapshot.gitBranch)
            MetadataLine("Commit", snapshot.gitCommitSha?.take(12))
            MetadataLine("Summary", snapshot.resultSummary)
            if (snapshot.state == "ERROR") {
                MetadataLine("Error", snapshot.errorMessage)
            }
            if (snapshot.artifacts.isNotEmpty()) {
                MetadataLine("Artifacts", snapshot.artifacts.joinToString(", "))
            }

            Spacer(Modifier.size(12.dp))
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(4.dp))

            if (narrative.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(
                        text = "Loading narrative…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(narrative, key = { it.timestamp + it.hashCode() }) { event ->
                        NarrativeRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun NarrativeRow(event: AgentNarrativeEvent) {
    val (icon, text) = when (event) {
        is AgentNarrativeEvent.AssistantText -> "💬" to event.text
        is AgentNarrativeEvent.ToolUse -> "🔧" to "${event.toolName}  ${event.paramsPreview}"
        is AgentNarrativeEvent.ToolResult -> {
            val mark = if (event.isError) "✗" else "✓"
            mark to event.outputPreview
        }
        is AgentNarrativeEvent.SystemEvent -> "⚙" to "${event.kind}  ${event.content}"
        is AgentNarrativeEvent.NarrativeUnavailable -> "⚠" to event.reason
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(icon, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
