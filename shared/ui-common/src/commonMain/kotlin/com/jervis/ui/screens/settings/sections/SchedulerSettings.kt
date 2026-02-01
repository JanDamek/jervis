package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ScheduledTaskDto
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTableRowCard
import com.jervis.ui.util.RefreshIconButton
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
fun SchedulerSettings(repository: JervisRepository) {
    var tasks by remember { mutableStateOf<List<ScheduledTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var taskToDelete by remember { mutableStateOf<ScheduledTaskDto?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                tasks = repository.scheduledTasks.listAllTasks()
            } catch (e: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    Column(modifier = Modifier.fillMaxSize()) {
        JActionBar {
            RefreshIconButton(onClick = { loadData() })
        }

        Spacer(Modifier.height(8.dp))

        if (isLoading && tasks.isEmpty()) {
            JCenteredLoading()
        } else if (tasks.isEmpty()) {
            JEmptyState(message = "Žádné naplánované úlohy")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks) { task ->
                    JTableRowCard(selected = false) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.taskName, style = MaterialTheme.typography.titleSmall)
                                task.cronExpression?.let {
                                    Text("Cron: $it", style = MaterialTheme.typography.labelSmall)
                                }
                                Text("Naplánováno: ${task.scheduledAt}", style = MaterialTheme.typography.bodySmall)
                            }
                            DeleteIconButton(onClick = { taskToDelete = task })
                        }
                    }
                }
            }
        }
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
                        loadData()
                    } catch (e: Exception) {}
                }
            }
        },
        onDismiss = { taskToDelete = null }
    )
}
