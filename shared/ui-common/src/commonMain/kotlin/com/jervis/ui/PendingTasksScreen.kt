package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.task.PendingTaskDto
import com.jervis.di.JervisRepository
import com.jervis.ui.coding.CodingAgentLogPanel
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

@Composable
fun PendingTasksScreen(
    repository: JervisRepository,
    clientId: String? = null,
    onBack: () -> Unit,
) {
    var tasks by remember { mutableStateOf<List<PendingTaskDto>>(emptyList()) }
    var totalTasks by remember { mutableStateOf(0L) }
    var currentPage by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedTaskType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedState by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSourceScheme by rememberSaveable { mutableStateOf<String?>(null) }

    val taskTypes = remember { com.jervis.dto.task.TaskTypeEnum.values().map { it.name } }
    val taskStates = remember { com.jervis.dto.task.TaskStateEnum.values().map { it.name } }
    // Phase 4: SourceUrn-scheme filter — matches the prefixes returned by
    // backend SourceUrn.scheme() and the kbSourceType mapping. Keep this list
    // in sync with SourceUrn.uiLabel() / kbSourceType().
    val sourceSchemes = remember {
        listOf(
            "email", "whatsapp", "slack", "discord", "teams",
            "jira", "github-issue", "gitlab-issue",
            "confluence", "git", "merge-request",
            "meeting", "calendar", "chat", "scheduled",
            "link", "doc", "idle-review", "user-task",
        )
    }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val result = repository.pendingTasks.listTasksPaged(
                    taskType = selectedTaskType,
                    state = selectedState,
                    page = 0,
                    pageSize = PAGE_SIZE,
                    clientId = clientId,
                    sourceScheme = selectedSourceScheme,
                    parentTaskId = null,
                    textQuery = null,
                )
                tasks = result.items
                totalTasks = result.totalCount
                currentPage = 0
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore || tasks.size.toLong() >= totalTasks) return
        scope.launch {
            isLoadingMore = true
            try {
                val nextPage = currentPage + 1
                val result = repository.pendingTasks.listTasksPaged(
                    taskType = selectedTaskType,
                    state = selectedState,
                    page = nextPage,
                    pageSize = PAGE_SIZE,
                    clientId = clientId,
                    sourceScheme = selectedSourceScheme,
                    parentTaskId = null,
                    textQuery = null,
                )
                tasks = tasks + result.items
                totalTasks = result.totalCount
                currentPage = nextPage
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent failure for load-more — user can scroll again
            } finally {
                isLoadingMore = false
            }
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            try {
                repository.pendingTasks.deletePendingTask(taskId)
                snackbarHostState.showSnackbar("Úloha byla úspěšně smazána")
                load()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar("Smazání úlohy selhalo: ${t.message}")
            }
        }
    }

    LaunchedEffect(selectedTaskType, selectedState, selectedSourceScheme) { load() }

    val listState = rememberLazyListState()

    // Infinite scroll: load more when near end
    LaunchedEffect(listState, tasks.size) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= tasks.size - 5
        }.collect { nearEnd ->
            if (nearEnd && tasks.size.toLong() < totalTasks) {
                loadMore()
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Úlohy ($totalTasks)",
                actions = {
                    RefreshIconButton(onClick = { load() })
                },
            )
        },
        snackbarHost = {
            JSnackbarHost(
                hostState = snackbarHostState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(JervisSpacing.outerPadding),
        ) {
            // Filters section
            JSection(title = "Filtry") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                    ) {
                        JDropdown(
                            items = listOf<String?>(null) + taskStates,
                            selectedItem = selectedState,
                            onItemSelected = { selectedState = it },
                            label = "Stav",
                            itemLabel = { it ?: "Vše" },
                            modifier = Modifier.weight(1f),
                        )
                        JDropdown(
                            items = listOf<String?>(null) + taskTypes,
                            selectedItem = selectedTaskType,
                            onItemSelected = { selectedTaskType = it },
                            label = "Pipeline",
                            itemLabel = { it ?: "Vše" },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    JDropdown(
                        items = listOf<String?>(null) + sourceSchemes,
                        selectedItem = selectedSourceScheme,
                        onItemSelected = { selectedSourceScheme = it },
                        label = "Zdroj",
                        itemLabel = { it ?: "Vše zdroje" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Task list
            when {
                isLoading && tasks.isEmpty() -> {
                    JCenteredLoading()
                }

                error != null -> {
                    JErrorState(
                        message = "Chyba při načítání: $error",
                        onRetry = { load() },
                    )
                }

                tasks.isEmpty() -> {
                    JEmptyState(message = "Žádné čekající úlohy", icon = "📋")
                }

                else -> {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                        ) {
                            items(tasks, key = { it.id }) { task ->
                                PendingTaskCard(
                                    task = task,
                                    repository = repository,
                                    onDelete = { pendingDeleteTaskId = task.id },
                                )
                            }
                            // Loading more indicator
                            if (isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                            // Remaining count hint
                            if (tasks.size.toLong() < totalTasks && !isLoadingMore) {
                                item {
                                    Text(
                                        text = "... a dalších ${totalTasks - tasks.size} úloh",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    ConfirmDialog(
        visible = pendingDeleteTaskId != null,
        title = "Smazat úlohu z fronty",
        message = "Opravdu chcete smazat tuto úlohu z fronty? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = {
            val id = pendingDeleteTaskId ?: return@ConfirmDialog
            pendingDeleteTaskId = null
            deleteTask(id)
        },
        onDismiss = { pendingDeleteTaskId = null },
    )
}

@Composable
private fun PendingTaskCard(
    task: PendingTaskDto,
    repository: JervisRepository,
    onDelete: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }

    JCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: task name + source label + delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" }
                            ?: task.sourceLabel.ifBlank { task.taskType },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    if (task.sourceLabel.isNotBlank()) {
                        Text(
                            text = task.sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DeleteIconButton(onClick = onDelete)
            }

            Spacer(Modifier.height(8.dp))

            // State chip + type chip + sub-task hint
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            getTaskStateLabel(task.state),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            getTaskTypeChipLabel(task.taskType),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                if (task.needsQualification) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                "Re-kvalifikace",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                }
                // Sub-task indicator
                if (task.parentTaskId != null) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                "↳ podúloha${task.phase?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        },
                    )
                } else if (task.childCount > 0) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                "${task.completedChildCount}/${task.childCount} dětí",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        },
                    )
                }
                // Show logs toggle for CODING tasks
                if (task.state == "CODING") {
                    SuggestionChip(
                        onClick = { showLogs = !showLogs },
                        label = {
                            Text(
                                if (showLogs) "Skrýt logy" else "Zobrazit logy",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            // Pending question — only when state=USER_TASK
            if (!task.pendingUserQuestion.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "❓ ${task.pendingUserQuestion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Created at — drop the cryptic 8-char clientId
            Text(
                text = "Vytvořeno: ${task.createdAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Content preview
            if (task.content.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = task.content.take(200) + if (task.content.length > 200) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Attachments count
            if (task.attachments.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Přílohy: ${task.attachments.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Live coding agent logs
            if (showLogs && task.state == "CODING") {
                Spacer(Modifier.height(12.dp))
                CodingAgentLogPanel(
                    jobLogsService = repository.jobLogs,
                    taskId = task.id,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * After Phase 1 the task type collapsed to INSTANT/SCHEDULED/SYSTEM. This is
 * still useful as a coarse pipeline indicator alongside the source label.
 * Source-specific labels (Email, WhatsApp, …) come from `task.sourceLabel`,
 * which is derived from SourceUrn.uiLabel() on the backend.
 */
private fun getTaskTypeChipLabel(taskType: String): String = when (taskType) {
    "INSTANT" -> "Chat"
    "SCHEDULED" -> "Plán"
    "SYSTEM" -> "Systém"
    else -> taskType
}

private fun getTaskStateLabel(state: String): String = when (state) {
    "NEW" -> "Nový"
    "INDEXING" -> "Indexace"
    "QUEUED" -> "Ve frontě"
    "PROCESSING" -> "Zpracovává se"
    "CODING" -> "Kódování"
    "USER_TASK" -> "K vyřízení"
    "BLOCKED" -> "Čeká na podúlohy"
    "DONE" -> "Dokončeno"
    "ERROR" -> "Chyba"
    else -> state
}
