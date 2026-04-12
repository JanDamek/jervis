package com.jervis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.di.JervisRepository
import com.jervis.dto.task.PendingTaskDto
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5 — chat-as-primary task sidebar.
 *
 * Shows all NON-DONE tasks (NEW / INDEXING / QUEUED / PROCESSING / USER_TASK
 * / BLOCKED / ERROR) as cards in the chat view, sorted newest-first. Click a
 * card → triggers [onTaskSelected] which the chat host wires to switch the
 * active conversation to that task's id.
 *
 * Auto-refresh every 15 s + manual refresh button. Once the chat host wires
 * the existing notification SSE feed in, the timer can be replaced with
 * push-based refresh.
 *
 * Filter chips to come in a follow-up; the v1 keeps all states visible so
 * the user instantly sees what JERVIS is doing across the whole pipeline.
 */
@Composable
fun ChatTaskSidebar(
    repository: JervisRepository,
    activeTaskId: String?,
    onTaskSelected: (PendingTaskDto) -> Unit,
    onMainChatSelected: () -> Unit,
    refreshTrigger: Int = 0,
    modifier: Modifier = Modifier,
) {
    var tasks by remember { mutableStateOf<List<PendingTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadActive() {
        isLoading = true
        error = null
        try {
            // Fetch up to 50 most-recent NON-terminal tasks across all states.
            // We fetch each non-DONE state separately and merge because the
            // server-side listTasksPaged accepts only ONE state filter at a
            // time. After Phase 5 follow-up the server query should accept
            // a list of states; this client merge keeps the v1 simple.
            val states = if (showDone) {
                // Show DONE only — user explicitly switched to history view
                listOf("DONE")
            } else {
                // Default — non-terminal active tasks
                listOf("USER_TASK", "PROCESSING", "QUEUED", "BLOCKED", "INDEXING", "NEW", "ERROR")
            }
            val merged = mutableListOf<PendingTaskDto>()
            for (state in states) {
                val page = repository.pendingTasks.listTasksPaged(
                    taskType = null,
                    state = state,
                    page = 0,
                    pageSize = 20,
                    clientId = null,
                    sourceScheme = null,
                    parentTaskId = null,
                    textQuery = null,
                )
                merged.addAll(page.items)
            }
            // Sort by priority bucket, then newest first within each bucket.
            // USER_TASK tasks float to the top (need user attention NOW),
            // running tasks next, waiting tasks, then errors last.
            tasks = merged.sortedWith(
                compareBy<PendingTaskDto> { statePriority(it.state) }
                    .thenByDescending { it.createdAt }
            ).take(50)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    // Initial load + reload on show/hide DONE toggle
    LaunchedEffect(showDone) { loadActive() }
    // Stream-based refresh: server pushes TASK_LIST_CHANGED via chatEventStream →
    // ChatViewModel increments sidebarRefreshTrigger → sidebar reloads immediately.
    // No 15s polling timer — per guidelines "no polling, use stream".
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) loadActive()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (showDone) "Hotové úlohy (${tasks.size})" else "Aktivní úlohy (${tasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showDone = !showDone },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (showDone) Icons.Default.CheckCircle else Icons.Default.History,
                            contentDescription = if (showDone) "Zobrazit aktivní" else "Zobrazit hotové",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { loadActive() } },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Obnovit",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // "Hlavní chat" entry — always at the top, returns to general conversation
            MainChatRow(
                isActive = activeTaskId == null,
                onClick = onMainChatSelected,
            )

            // Loading / error / empty states
            when {
                isLoading && tasks.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                error != null -> {
                    Text(
                        text = "Chyba: $error",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                tasks.isEmpty() -> {
                    Text(
                        text = "Žádné aktivní úlohy. JERVIS čeká na nový obsah.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            ChatSidebarTaskCard(
                                task = task,
                                isActive = task.id == activeTaskId,
                                onClick = { onTaskSelected(task) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainChatRow(
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "💬 Hlavní chat",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ChatSidebarTaskCard(
    task: PendingTaskDto,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Task name + state badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = task.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" }
                        ?: task.sourceLabel.ifBlank { "Úloha" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StateBadge(task.state, task.needsQualification)
            }

            // Source label + sub-task hint + needs-qualification
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (task.sourceLabel.isNotBlank()) {
                    Text(
                        text = task.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.parentTaskId != null) {
                    Text(
                        text = "↳ podúloha",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else if (task.childCount > 0) {
                    Text(
                        text = "${task.completedChildCount}/${task.childCount} dětí",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (task.needsQualification) {
                    Text(
                        text = "● re-kvalifikace",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            // Pending question line — prominent for USER_TASK
            if (!task.pendingUserQuestion.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "❓ ${task.pendingUserQuestion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Sidebar sort order: lower number = higher in the list. */
private fun statePriority(state: String): Int = when (state) {
    "USER_TASK" -> 0   // needs user attention — always on top
    "PROCESSING" -> 1  // actively running
    "CODING" -> 1
    "QUEUED" -> 2      // waiting for orchestrator
    "NEW" -> 3         // fresh / being indexed
    "INDEXING" -> 3
    "BLOCKED" -> 4     // waiting for children
    "ERROR" -> 5       // failed — bottom
    "DONE" -> 6        // only visible in history toggle
    else -> 5
}

@Composable
private fun StateBadge(state: String, needsQualification: Boolean = false) {
    val (label, color) = if (needsQualification) {
        "Kvalifikátor" to Color(0xFF6A1B9A)
    } else when (state) {
        "USER_TASK" -> "K vyřízení" to Color(0xFF1976D2)
        "ERROR" -> "Chyba" to Color(0xFFD32F2F)
        "QUEUED" -> "Čeká na JERVIS" to Color(0xFFF57C00)
        "PROCESSING" -> "JERVIS pracuje" to Color(0xFFF57C00)
        "CODING" -> "Kódování" to Color(0xFFF57C00)
        "INDEXING" -> "Indexace" to Color(0xFF7B1FA2)
        "BLOCKED" -> "Čeká na podúlohy" to Color(0xFF757575)
        "NEW" -> "Nový" to Color(0xFF7B1FA2)
        "DONE" -> "Hotovo" to Color(0xFF388E3C)
        else -> state to Color(0xFF757575)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
