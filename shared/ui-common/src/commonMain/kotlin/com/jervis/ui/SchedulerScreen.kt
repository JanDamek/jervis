package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    var tasks by remember { mutableStateOf<List<EnhancedScheduledTask>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }

    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var selectedTask by remember { mutableStateOf<EnhancedScheduledTask?>(null) }

    var taskInstruction by remember { mutableStateOf("") }
    var taskName by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("0") }

    var isLoading by remember { mutableStateOf(false) }
    var isLoadingTasks by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var projectDropdownExpanded by remember { mutableStateOf(false) }
    var pendingOnly by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load scheduled tasks
    fun loadTasks() {
        scope.launch {
            isLoadingTasks = true
            errorMessage = null
            try {
                val allTasks = if (pendingOnly) {
                    repository.scheduledTasks.listPendingTasks()
                } else {
                    repository.scheduledTasks.listAllTasks()
                }

                // Enhance tasks with project and client names
                tasks = allTasks.map { task ->
                    val project = projects.find { it.id == task.projectId }
                    val client = project?.clientId?.let { clientId ->
                        clients.find { it.id == clientId }
                    }
                    EnhancedScheduledTask(
                        task = task,
                        projectName = project?.name ?: "Unknown",
                        clientName = client?.name
                    )
                }.sortedByDescending { it.task.scheduledAt }
            } catch (e: Exception) {
                errorMessage = "Failed to load tasks: ${e.message}"
            } finally {
                isLoadingTasks = false
            }
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.listClients()
            projects = repository.projects.getAllProjects()
            if (clients.isNotEmpty()) {
                selectedClient = clients[0]
                if (projects.isNotEmpty()) {
                    selectedProject = projects.find { it.clientId == clients[0].id }
                }
            }
            loadTasks()
        } catch (e: Exception) {
            errorMessage = "Failed to load data: ${e.message}"
        }
    }

    // Load projects for selected client
    fun loadProjectsForClient(clientId: String?) {
        if (clientId == null) return
        val clientProjects = projects.filter { it.clientId == clientId }
        selectedProject = if (clientProjects.isNotEmpty()) clientProjects[0] else null
    }

    // Schedule task
    fun scheduleTask() {
        val project = selectedProject
        if (project == null) {
            errorMessage = "Please select a project"
            return
        }

        if (taskInstruction.trim().isEmpty()) {
            errorMessage = "Please enter task instruction"
            return
        }

        val taskNameFinal = taskName.ifBlank { "Task: ${taskInstruction.take(50)}" }
        val priorityInt = priority.toIntOrNull() ?: 0

        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                repository.scheduledTasks.scheduleTask(
                    projectId = project.id ?: return@launch,
                    taskName = taskNameFinal,
                    taskInstruction = taskInstruction,
                    cronExpression = cronExpression.ifBlank { null },
                    priority = priorityInt
                )

                taskInstruction = ""
                taskName = ""
                cronExpression = ""
                priority = "0"
                loadTasks()
            } catch (e: Exception) {
                errorMessage = "Failed to schedule task: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Delete task
    fun deleteTask() {
        val task = selectedTask ?: return
        scope.launch {
            try {
                repository.scheduledTasks.cancelTask(task.task.id)
                selectedTask = null
                showDeleteConfirm = false
                loadTasks()
            } catch (e: Exception) {
                errorMessage = "Failed to delete task: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task Scheduler") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pending Only")
                        Switch(checked = pendingOnly, onCheckedChange = {
                            pendingOnly = it
                            loadTasks()
                        })
                        com.jervis.ui.util.RefreshIconButton(onClick = { loadTasks() })
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Left panel - Create task form
            Column(
                modifier = Modifier.width(400.dp).fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Create Scheduled Task", style = MaterialTheme.typography.titleMedium)

                // Client dropdown
                ExposedDropdownMenuBox(
                    expanded = clientDropdownExpanded,
                    onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedClient?.name ?: "Select Client",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Client") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        enabled = !isLoading
                    )
                    ExposedDropdownMenu(
                        expanded = clientDropdownExpanded,
                        onDismissRequest = { clientDropdownExpanded = false }
                    ) {
                        clients.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client.name) },
                                onClick = {
                                    selectedClient = client
                                    loadProjectsForClient(client.id)
                                    clientDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Project dropdown
                ExposedDropdownMenuBox(
                    expanded = projectDropdownExpanded,
                    onExpandedChange = { projectDropdownExpanded = !projectDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedProject?.name ?: "Select Project",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Project") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        enabled = !isLoading
                    )
                    ExposedDropdownMenu(
                        expanded = projectDropdownExpanded,
                        onDismissRequest = { projectDropdownExpanded = false }
                    ) {
                        projects.filter { it.clientId == selectedClient?.id }.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    selectedProject = project
                                    projectDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text("Task Name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = taskInstruction,
                    onValueChange = { taskInstruction = it },
                    label = { Text("Task Instruction") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    minLines = 4,
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = cronExpression,
                    onValueChange = { cronExpression = it },
                    label = { Text("Cron Expression (optional)") },
                    placeholder = { Text("e.g. 0 0 * * *") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text("Priority") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                Button(
                    onClick = { scheduleTask() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && selectedProject != null && taskInstruction.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "Scheduling..." else "📅 Schedule Task")
                }

                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            VerticalDivider()

            // Right panel - Scheduled tasks list
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                // Task list
                Column(
                    modifier = Modifier.weight(0.6f).fillMaxHeight()
                ) {
                    Text(
                        text = "Scheduled Tasks (${tasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    when {
                        isLoadingTasks -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        tasks.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No scheduled tasks",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(tasks) { task ->
                                    ScheduledTaskCard(
                                        task = task,
                                        isSelected = selectedTask == task,
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

                VerticalDivider()

                // Task detail
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight()
                ) {
                    Text(
                        text = "Task Details",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    if (selectedTask != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SchedulerDetailField("Task Name", selectedTask!!.task.taskName)
                            SchedulerDetailField("Status", selectedTask!!.task.status.name)
                            SchedulerDetailField("Priority", selectedTask!!.task.priority.toString())
                            SchedulerDetailField("Project", selectedTask!!.projectName)
                            selectedTask!!.clientName?.let {
                                SchedulerDetailField("Client", it)
                            }
                            selectedTask!!.task.cronExpression?.let {
                                SchedulerDetailField("Cron", it)
                            }
                            SchedulerDetailField("Scheduled", formatInstant(selectedTask!!.task.scheduledAt))
                            selectedTask!!.task.completedAt?.let {
                                SchedulerDetailField("Completed", formatInstant(it))
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Instruction:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = selectedTask!!.task.taskInstruction,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            selectedTask!!.task.errorMessage?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Error:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
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

    // Delete confirmation dialog
    com.jervis.ui.util.ConfirmDialog(
        visible = showDeleteConfirm && selectedTask != null,
        title = "Delete Scheduled Task",
        message = "Are you sure you want to delete this scheduled task? This action cannot be undone.",
        confirmText = "Delete",
        onConfirm = { deleteTask() },
        onDismiss = { showDeleteConfirm = false }
    )
}

data class EnhancedScheduledTask(
    val task: ScheduledTaskDto,
    val projectName: String,
    val clientName: String?
)

@Composable
private fun ScheduledTaskCard(
    task: EnhancedScheduledTask,
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
                Text(
                    text = task.task.taskName,
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Badge { Text(task.task.status.name) }
                    Badge { Text("P${task.task.priority}") }
                }
                Text(
                    text = "${task.projectName}${task.clientName?.let { " • $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = formatInstant(task.task.scheduledAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            com.jervis.ui.util.DeleteIconButton(
                onClick = { onDelete() }
            )
        }
    }
}

@Composable
private fun SchedulerDetailField(label: String, value: String) {
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

private fun formatInstant(epochMillis: Long): String {
    // Simple formatting - can be improved with kotlinx-datetime
    return epochMillis.toString() // Placeholder
}
