package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.PendingTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun PendingTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var tasks by remember { mutableStateOf<List<PendingTaskDto>>(emptyList()) }
    var totalTasks by remember { mutableStateOf(0L) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedTaskType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedState by rememberSaveable { mutableStateOf<String?>(null) }

    val taskTypes = remember { com.jervis.dto.TaskTypeEnum.values().map { it.name } }
    val taskStates = remember { com.jervis.dto.TaskStateEnum.values().map { it.name } }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching {
                tasks = repository.pendingTasks.listTasks(selectedTaskType, selectedState)
                totalTasks = repository.pendingTasks.countTasks(selectedTaskType, selectedState)
            }.onFailure { error = it.message }
            isLoading = false
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            try {
                repository.pendingTasks.deletePendingTask(taskId)
                snackbarHostState.showSnackbar("Úloha byla úspěšně smazána")
                load()
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar("Smazání úlohy selhalo: ${t.message}")
            }
        }
    }

    LaunchedEffect(selectedTaskType, selectedState) { load() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Fronta úloh ($totalTasks)",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JDropdown(
                        items = listOf<String?>(null) + taskTypes,
                        selectedItem = selectedTaskType,
                        onItemSelected = { selectedTaskType = it },
                        label = "Typ úlohy",
                        itemLabel = { it ?: "Vše" },
                        modifier = Modifier.weight(1f),
                    )
                    JDropdown(
                        items = listOf<String?>(null) + taskStates,
                        selectedItem = selectedState,
                        onItemSelected = { selectedState = it },
                        label = "Stav",
                        itemLabel = { it ?: "Vše" },
                        modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                        ) {
                            items(tasks) { task ->
                                PendingTaskCard(
                                    task = task,
                                    onDelete = { pendingDeleteTaskId = task.id },
                                )
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
    onDelete: () -> Unit,
) {
    JCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: task type + delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = getTaskTypeLabel(task.taskType),
                    style = MaterialTheme.typography.titleMedium,
                )
                DeleteIconButton(onClick = onDelete)
            }

            Spacer(Modifier.height(8.dp))

            // State chip + project chip
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
                task.projectId?.let {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                "Projekt: ${it.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Client and creation time
            Text(
                text = "Klient: ${task.clientId.take(8)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }
    }
}

private fun getTaskTypeLabel(taskType: String): String = when (taskType) {
    "EMAIL_PROCESSING" -> "Zpracování emailu"
    "BUGTRACKER_PROCESSING" -> "Zpracování bug trackeru"
    "LINK_PROCESSING" -> "Zpracování odkazu"
    "WIKI_PROCESSING" -> "Zpracování wiki"
    "GIT_PROCESSING" -> "Zpracování gitu"
    "MEETING_PROCESSING" -> "Zpracování schůzky"
    "USER_INPUT_PROCESSING" -> "Uživatelský vstup"
    "USER_TASK" -> "Uživatelská úloha"
    "SCHEDULED_TASK" -> "Plánovaná úloha"
    else -> taskType
}

private fun getTaskStateLabel(state: String): String = when (state) {
    "NEW" -> "Nový"
    "READY_FOR_QUALIFICATION" -> "K kvalifikaci"
    "QUALIFYING" -> "Kvalifikace"
    "READY_FOR_GPU" -> "Připraven pro GPU"
    "DISPATCHED_GPU" -> "Odeslán na GPU"
    "PYTHON_ORCHESTRATING" -> "Orchestrace"
    "USER_TASK" -> "Uživatelská úloha"
    "ERROR" -> "Chyba"
    else -> state
}
