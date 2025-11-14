package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.util.rememberClipboardManager
import kotlinx.coroutines.launch
import com.jervis.ui.design.JTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    val clipboard = rememberClipboardManager()
    var tasks by remember { mutableStateOf<List<UserTaskDto>>(emptyList()) }
    var allTasks by remember { mutableStateOf<List<UserTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterText by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<UserTaskDto?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var additionalInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Apply filter
    fun applyFilter() {
        val query = filterText.trim().lowercase()
        tasks = if (query.isBlank()) {
            allTasks
        } else {
            allTasks.filter { task ->
                task.title.lowercase().contains(query) ||
                (task.description?.lowercase()?.contains(query) == true) ||
                task.sourceType.lowercase().contains(query) ||
                (task.projectId?.lowercase()?.contains(query) == true)
            }
        }
    }

    // Load tasks from all clients
    fun loadTasks() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val clients = repository.clients.listClients()
                val allTasksList = mutableListOf<UserTaskDto>()

                for (client in clients) {
                    try {
                        client.id?.let { clientId ->
                            val clientTasks = repository.userTasks.listActive(clientId)
                            allTasksList.addAll(clientTasks)
                        }
                    } catch (e: Exception) {
                        // Continue loading other clients' tasks even if one fails
                    }
                }

                // Sort by age (older first)
                allTasks = allTasksList.sortedBy { it.createdAtEpochMillis }
                applyFilter()
            } catch (e: Exception) {
                errorMessage = "Failed to load tasks: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Handle delete
    fun handleDelete() {
        val task = selectedTask ?: return
        scope.launch {
            try {
                repository.userTasks.cancel(task.id)
                showDeleteConfirm = false
                selectedTask = null
                loadTasks()
            } catch (e: Exception) {
                errorMessage = "Failed to delete task: ${e.message}"
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) {
        loadTasks()
    }

    // Apply filter when filterText changes
    LaunchedEffect(filterText) {
        applyFilter()
    }

    // Clear additional input when selected task changes
    LaunchedEffect(selectedTask) {
        additionalInput = ""
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "User Tasks",
                onBack = onBack,
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { loadTasks() })
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Filter field
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text("Filter") },
                placeholder = { Text("Search by title, description, source, or project...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main content area
            Row(
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Tasks list (left side)
                Card(
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header with action buttons
                        Text(
                            text = "Tasks (${tasks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )

                        HorizontalDivider()

                        when {
                            isLoading -> {
                                com.jervis.ui.design.JCenteredLoading()
                            }
                            errorMessage != null -> {
                                com.jervis.ui.design.JErrorState(
                                    message = errorMessage!!,
                                    onRetry = { loadTasks() }
                                )
                            }
                            tasks.isEmpty() -> {
                                com.jervis.ui.design.JEmptyState(message = "No tasks found")
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(tasks) { task ->
                                        UserTaskRow(
                                            task = task,
                                            isSelected = selectedTask?.id == task.id,
                                            onClick = { selectedTask = task },
                                            onDelete = {
                                                selectedTask = task
                                                showDeleteConfirm = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Task details (right side)
                Card(
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Task Details",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                        HorizontalDivider()

                        if (selectedTask != null) {
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // Scrollable details
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TaskDetailField("Title", selectedTask!!.title)
                                    TaskDetailField("Priority", selectedTask!!.priority)
                                    TaskDetailField("Status", selectedTask!!.status)
                                    selectedTask!!.dueDateEpochMillis?.let {
                                        TaskDetailField("Due", formatDateTime(it))
                                    }
                                    TaskDetailField("Project", selectedTask!!.projectId ?: "-")
                                    TaskDetailField("Source Type", selectedTask!!.sourceType)

                                    if (!selectedTask!!.sourceUri.isNullOrBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Source Link:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(selectedTask!!.sourceUri!!))
                                            }) {
                                                Text("Copy")
                                            }
                                        }
                                        SelectionContainer {
                                            Text(
                                                text = selectedTask!!.sourceUri!!,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }

                                    if (!selectedTask!!.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Description:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(selectedTask!!.description!!))
                                            }) {
                                                Text("Copy")
                                            }
                                        }
                                        SelectionContainer {
                                            Text(
                                                text = selectedTask!!.description!!,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }

                                // Input area for additional instructions
                                HorizontalDivider()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Additional Instructions (optional):",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = additionalInput,
                                        onValueChange = { additionalInput = it },
                                        modifier = Modifier.fillMaxWidth().height(100.dp),
                                        placeholder = { Text("Add any additional context or instructions...") },
                                        maxLines = 4,
                                        enabled = !isSending
                                    )

                                    // Direct send buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isSending = true
                                                    try {
                                                        repository.userTasks.sendToAgent(
                                                            selectedTask!!.id,
                                                            TaskRoutingMode.DIRECT_TO_AGENT,
                                                            additionalInput.takeIf { it.isNotBlank() }
                                                        )
                                                        additionalInput = ""
                                                        loadTasks()
                                                    } catch (e: Exception) {
                                                        errorMessage = e.message ?: "Failed to send to agent"
                                                    } finally {
                                                        isSending = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isSending,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text("⚡ To Agent")
                                        }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isSending = true
                                                    try {
                                                        repository.userTasks.sendToAgent(
                                                            selectedTask!!.id,
                                                            TaskRoutingMode.BACK_TO_PENDING,
                                                            additionalInput.takeIf { it.isNotBlank() }
                                                        )
                                                        additionalInput = ""
                                                        loadTasks()
                                                    } catch (e: Exception) {
                                                        errorMessage = e.message ?: "Failed to send to pending"
                                                    } finally {
                                                        isSending = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isSending,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary
                                            )
                                        ) {
                                            Text("📋 To Pending")
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Select a task to view details",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    com.jervis.ui.util.ConfirmDialog(
        visible = showDeleteConfirm && selectedTask != null,
        title = "Delete User Task",
        message = "Are you sure you want to delete this task? This action cannot be undone.",
        confirmText = "Delete",
        onConfirm = { handleDelete() },
        onDismiss = { showDeleteConfirm = false }
    )
}

@Composable
private fun UserTaskRow(
    task: UserTaskDto,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Badge { Text(task.priority) }
                            Badge { Text(task.status) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = task.sourceType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        task.dueDateEpochMillis?.let {
                            Text(
                                text = formatDate(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!task.projectId.isNullOrBlank()) {
                    Text(
                        text = "Project: ${task.projectId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            com.jervis.ui.util.DeleteIconButton(
                onClick = { onDelete() }
            )
        }
    }
}

@Composable
private fun TaskDetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDate(epochMillis: Long): String {
    // Simple formatting - in real app use platform-specific date formatter
    return epochMillis.toString() // Placeholder
}

private fun formatDateTime(epochMillis: Long): String {
    return formatDate(epochMillis)
}
