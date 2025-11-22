package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.PendingTaskDto
import com.jervis.dto.PendingTaskState
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var selectedTaskType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedState by rememberSaveable { mutableStateOf<String?>(null) }

    val taskTypes = remember { PendingTaskTypeEnum.values().map { it.name } }
    val taskStates = remember { PendingTaskState.values().map { it.name } }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching {
                tasks = repository.pendingTasks.listPendingTasks(selectedTaskType, selectedState)
                totalTasks = repository.pendingTasks.countPendingTasks(selectedTaskType, selectedState)
            }
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

    LaunchedEffect(selectedTaskType, selectedState) { load() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Pending Tasks ($totalTasks)",
                onBack = onBack,
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { load() })
                }
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterDropdown(
                    label = "Task Type",
                    items = taskTypes,
                    selectedItem = selectedTaskType,
                    onItemSelected = { selectedTaskType = it }
                )
                FilterDropdown(
                    label = "State",
                    items = taskStates,
                    selectedItem = selectedState,
                    onItemSelected = { selectedState = it }
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> {
                        com.jervis.ui.design.JCenteredLoading()
                    }
                    error != null -> {
                        com.jervis.ui.design.JErrorState(
                            message = "Failed to load: $error",
                            onRetry = { load() }
                        )
                    }
                    tasks.isEmpty() -> {
                        com.jervis.ui.design.JEmptyState(message = "No pending tasks")
                    }
                    else -> {
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
                                        com.jervis.ui.util.DeleteIconButton(
                                            onClick = { pendingDeleteTaskId = task.id },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDropdown(
    label: String,
    items: List<String>,
    selectedItem: String?,
    onItemSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            value = selectedItem ?: "All",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All") },
                onClick = {
                    onItemSelected(null)
                    expanded = false
                }
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}
