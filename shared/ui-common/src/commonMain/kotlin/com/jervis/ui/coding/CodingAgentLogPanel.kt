package com.jervis.ui.coding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jervis.dto.git.JobLogEventDto
import com.jervis.service.meeting.IJobLogsService
import kotlinx.coroutines.CancellationException

/**
 * Terminal-like live log panel for coding agent K8s Jobs.
 *
 * Subscribes to IJobLogsService.subscribeToJobLogs(taskId) and renders
 * log events in a scrollable, auto-scrolling monospace view.
 */
@Composable
fun CodingAgentLogPanel(
    jobLogsService: IJobLogsService,
    taskId: String,
    modifier: Modifier = Modifier,
) {
    val logLines = remember { mutableStateListOf<JobLogEventDto>() }
    var isStreaming by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Subscribe to log stream
    LaunchedEffect(taskId) {
        logLines.clear()
        isStreaming = true
        try {
            jobLogsService.subscribeToJobLogs(taskId).collect { event ->
                logLines.add(event)
                if (event.type == "done" || event.type == "error") {
                    isStreaming = false
                }
            }
        } catch (_: CancellationException) {
            // Normal cancellation (e.g., composable left composition)
        } catch (_: Exception) {
            isStreaming = false
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Výstup agenta",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isStreaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Běží...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Log area — terminal-style dark background
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 400.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logLines) { event ->
                LogLine(event)
            }
        }
    }
}

@Composable
private fun LogLine(event: JobLogEventDto) {
    val (prefix, color) = when (event.type) {
        "text" -> "" to MaterialTheme.colorScheme.onSurface
        "tool_call" -> "\u25B6 " to MaterialTheme.colorScheme.primary  // ▶
        "result" -> "\u2713 " to MaterialTheme.colorScheme.tertiary     // ✓
        "status" -> "\u2139 " to MaterialTheme.colorScheme.onSurfaceVariant // ℹ
        "error" -> "\u2717 " to MaterialTheme.colorScheme.error         // ✗
        "log" -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        "done" -> "\u2714 " to MaterialTheme.colorScheme.primary        // ✔
        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = prefix + event.content,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}
