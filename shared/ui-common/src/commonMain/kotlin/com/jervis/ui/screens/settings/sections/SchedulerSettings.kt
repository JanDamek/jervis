package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ScheduledTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SchedulerSettings(repository: JervisRepository) {
    var tasks by remember { mutableStateOf<List<ScheduledTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var taskToDelete by remember { mutableStateOf<ScheduledTaskDto?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                tasks = repository.scheduledTasks.listAllTasks()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
            }

            Spacer(Modifier.height(JervisSpacing.itemGap))

            if (isLoading && tasks.isEmpty()) {
                JCenteredLoading()
            } else if (tasks.isEmpty()) {
                JEmptyState(message = "Žádné naplánované úlohy", icon = "📅")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                    modifier = Modifier.weight(1f),
                ) {
                    items(tasks) { task ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .heightIn(min = JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.taskName, style = MaterialTheme.typography.titleMedium)
                                    task.cronExpression?.let {
                                        Text(
                                            "Cron: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        "Naplánováno: ${formatScheduledAt(task.scheduledAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DeleteIconButton(onClick = { taskToDelete = task })
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }

    ConfirmDialog(
        visible = taskToDelete != null,
        title = "Zrušit úlohu",
        message = "Opravdu chcete zrušit naplánovanou úlohu '${taskToDelete?.taskName}'?",
        confirmText = "Zrušit",
        onConfirm = {
            val task = taskToDelete
            taskToDelete = null
            if (task != null) {
                scope.launch {
                    try {
                        repository.scheduledTasks.cancelTask(task.id)
                        snackbarHostState.showSnackbar("Úloha zrušena")
                        loadData()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            }
        },
        onDismiss = { taskToDelete = null },
        isDestructive = true,
    )
}

private fun formatScheduledAt(epochMillis: Long): String {
    return try {
        val instant = Instant.fromEpochMilliseconds(epochMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.dayOfMonth}.${local.monthNumber}.${local.year} " +
            "${local.hour.toString().padStart(2, '0')}:" +
            "${local.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        epochMillis.toString()
    }
}
