package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.PendingTaskDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var tasks by remember { mutableStateOf<List<PendingTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDeleteTaskId by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.pendingTasks.listPendingTasks() }
                .onSuccess { tasks = it }
                .onFailure { error = it.message }
            isLoading = false
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            try {
                repository.pendingTasks.deletePendingTask(taskId)
                infoMessage = "Task deleted successfully"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to delete task: ${t.message}"
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending Tasks") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { load() })
                }
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null ->
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Failed to load: $error", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { load() }) { Text("Retry") }
                    }
                tasks.isEmpty() ->
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No pending tasks", style = MaterialTheme.typography.bodyLarge)
                    }
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        items(tasks) { task ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(task.taskType, style = MaterialTheme.typography.titleMedium)
                                        com.jervis.ui.util.IconButtons.DeleteIconButton(
                                            onClick = { pendingDeleteTaskId = task.id }
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        AssistChip(onClick = {}, label = { Text(task.state) })
                                        task.projectId?.let {
                                            AssistChip(onClick = {}, label = { Text("Project: ${it.take(8)}") })
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Client: ${task.clientId.take(8)}...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Created: ${task.createdAt}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        task.content.take(200) + if (task.content.length > 200) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
            }
        }

        // Delete confirmation dialog
        com.jervis.ui.util.ConfirmDialog(
            visible = pendingDeleteTaskId != null,
            title = "Delete Pending Task",
            message = "Are you sure you want to delete this pending task? This action cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                val id = pendingDeleteTaskId ?: return@ConfirmDialog
                pendingDeleteTaskId = null
                deleteTask(id)
            },
            onDismiss = { pendingDeleteTaskId = null },
        )

        // Info message snackbar
        infoMessage?.let {
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(3000)
                infoMessage = null
            }
            Snackbar(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(it)
            }
        }
    }
}
