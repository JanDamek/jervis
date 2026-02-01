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
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
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
                val allTasks = repository.scheduledTasks.listAllTasks()

                // Enhance tasks with project and client names
                tasks = allTasks.map { task ->
                    val client = clients.find { it.id == task.clientId }
                    val project = task.projectId?.let { projId ->
                        projects.find { it.id == projId }
                    }
                    EnhancedScheduledTask(
                        task = task,
                        projectName = project?.name ?: "Unknown",
                        clientName = client?.name ?: "Unknown"
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
        val client = selectedClient
        if (project == null || client == null) {
            errorMessage = "Please select a client and project"
            return
        }

        if (taskInstruction.trim().isEmpty()) {
            errorMessage = "Please enter task instruction"
            return
        }

        val taskNameFinal = taskName.ifBlank { "Task: ${taskInstruction.take(50)}" }

        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                repository.scheduledTasks.scheduleTask(
                    clientId = client.id ?: return@launch,
                    projectId = project.id,
                    taskName = taskNameFinal,
                    content = taskInstruction,
                    cronExpression = cronExpression.ifBlank { null },
                    correlationId = null
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
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Plánovač úloh",
                onBack = onBack,
                actions = {
                    RefreshIconButton(onClick = { loadTasks() })
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
                JSection(title = "Naplánovat novou úlohu") {
                    // Client dropdown
                    ExposedDropdownMenuBox(
                        expanded = clientDropdownExpanded,
                        onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedClient?.name ?: "Vyberte klienta",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Klient") },
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

                    Spacer(Modifier.height(8.dp))

                    // Project dropdown
                    ExposedDropdownMenuBox(
                        expanded = projectDropdownExpanded,
                        onExpandedChange = { projectDropdownExpanded = !projectDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedProject?.name ?: "Vyberte projekt",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Projekt") },
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

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = taskName,
                        onValueChange = { taskName = it },
                        label = { Text("Název úlohy (volitelné)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    OutlinedTextField(
                        value = taskInstruction,
                        onValueChange = { taskInstruction = it },
                        label = { Text("Instrukce pro agenta") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        minLines = 4,
                        enabled = !isLoading
                    )

                    OutlinedTextField(
                        value = cronExpression,
                        onValueChange = { cronExpression = it },
                        label = { Text("Cron výraz (volitelné)") },
                        placeholder = { Text("např. 0 0 * * *") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { scheduleTask() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && selectedProject != null && taskInstruction.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Plánuji..." else "📅 Naplánovat úlohu")
                    }
                }

                errorMessage?.let { error ->
                    JErrorState(message = error)
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
                        text = "Naplánované úlohy (${tasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    when {
                        isLoadingTasks -> {
                            JCenteredLoading()
                        }
                        tasks.isEmpty() -> {
                            JEmptyState(message = "Žádné naplánované úlohy")
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(tasks) { task ->
                                    ScheduledTaskRow(
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
                        text = "Detaily úlohy",
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
                            JSection {
                                SchedulerDetailField("Název úlohy", selectedTask!!.task.taskName)
                                SchedulerDetailField("Klient", selectedTask!!.clientName)
                                SchedulerDetailField("Projekt", selectedTask!!.projectName)
                                selectedTask!!.task.cronExpression?.let {
                                    SchedulerDetailField("Cron", it)
                                }
                                SchedulerDetailField("Naplánováno", formatInstant(selectedTask!!.task.scheduledAt))
                                selectedTask!!.task.correlationId?.let {
                                    SchedulerDetailField("Correlation ID", it)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Instrukce:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            JTableRowCard(selected = false) {
                                Text(
                                    text = selectedTask!!.task.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Vyberte úlohu pro zobrazení detailů",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    ConfirmDialog(
        visible = showDeleteConfirm && selectedTask != null,
        title = "Smazat naplánovanou úlohu",
        message = "Opravdu chcete smazat naplánovanou úlohu '${selectedTask?.task?.taskName}'? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = { deleteTask() },
        onDismiss = { showDeleteConfirm = false }
    )
}

data class EnhancedScheduledTask(
    val task: ScheduledTaskDto,
    val projectName: String,
    val clientName: String
)

@Composable
private fun ScheduledTaskRow(
    task: EnhancedScheduledTask,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    JTableRowCard(
        selected = isSelected,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
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
                task.task.cronExpression?.let { cron ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Badge { Text("🔁 $cron") }
                    }
                }
                Text(
                    text = "${task.clientName} • ${task.projectName}",
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

            DeleteIconButton(onClick = onDelete)
        }
    }
}

@Composable
private fun SchedulerDetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
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
