package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var tasks by remember { mutableStateOf<List<EnhancedScheduledTask>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }

    var selectedTask by remember { mutableStateOf<EnhancedScheduledTask?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<EnhancedScheduledTask?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun loadTasks() {
        scope.launch {
            isLoading = true
            try {
                val allTasks = repository.scheduledTasks.listAllTasks()
                tasks = allTasks.map { task ->
                    val client = clients.find { it.id == task.clientId }
                    val project = task.projectId?.let { projId ->
                        projects.find { it.id == projId }
                    }
                    EnhancedScheduledTask(
                        task = task,
                        projectName = project?.name ?: "Neznámý",
                        clientName = client?.name ?: "Neznámý",
                    )
                }.sortedByDescending { it.task.scheduledAt }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba načítání úloh: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            clients = repository.clients.getAllClients()
            projects = repository.projects.getAllProjects()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání dat: ${e.message}")
        }
        loadTasks()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        JListDetailLayout(
            items = tasks,
            selectedItem = selectedTask,
            isLoading = isLoading,
            onItemSelected = { selectedTask = it },
            emptyMessage = "Žádné naplánované úlohy",
            emptyIcon = "📅",
            listHeader = {
                JTopBar(
                    title = "Plánovač úloh",
                    onBack = onBack,
                    actions = {
                        RefreshIconButton(onClick = { loadTasks() })
                        Spacer(Modifier.width(8.dp))
                        JPrimaryButton(onClick = { showCreateDialog = true }) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Nová úloha")
                        }
                    },
                )
            },
            listItem = { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTask = task },
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .heightIn(min = JervisSpacing.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.task.taskName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${task.clientName} · ${task.projectName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    formatInstant(task.task.scheduledAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                task.task.cronExpression?.let { cron ->
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(cron, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            detailContent = { task ->
                ScheduledTaskDetail(
                    task = task,
                    onBack = { selectedTask = null },
                    onDelete = { showDeleteConfirm = task },
                )
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }

    if (showCreateDialog) {
        ScheduleTaskDialog(
            clients = clients,
            projects = projects,
            onDismiss = { showCreateDialog = false },
            onCreate = { clientId, projectId, name, content, cron ->
                scope.launch {
                    try {
                        repository.scheduledTasks.scheduleTask(
                            clientId = clientId,
                            projectId = projectId,
                            taskName = name,
                            content = content,
                            cronExpression = cron,
                            correlationId = null,
                        )
                        snackbarHostState.showSnackbar("Úloha naplánována")
                        showCreateDialog = false
                        loadTasks()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
        )
    }

    showDeleteConfirm?.let { task ->
        ConfirmDialog(
            visible = true,
            title = "Smazat naplánovanou úlohu",
            message = "Opravdu chcete smazat úlohu '${task.task.taskName}'? Tuto akci nelze vrátit.",
            confirmText = "Smazat",
            onConfirm = {
                scope.launch {
                    try {
                        repository.scheduledTasks.cancelTask(task.task.id)
                        snackbarHostState.showSnackbar("Úloha smazána")
                        selectedTask = null
                        showDeleteConfirm = null
                        loadTasks()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
            onDismiss = { showDeleteConfirm = null },
            isDestructive = true,
        )
    }
}

data class EnhancedScheduledTask(
    val task: ScheduledTaskDto,
    val projectName: String,
    val clientName: String,
)

// ── Detail Screen ────────────────────────────────────────────────────────────────

@Composable
private fun ScheduledTaskDetail(
    task: EnhancedScheduledTask,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    JDetailScreen(
        title = task.task.taskName,
        onBack = onBack,
        actions = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Smazat")
            }
        },
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JSection(title = "Základní údaje") {
                DetailField("Název úlohy", task.task.taskName)
                Spacer(Modifier.height(JervisSpacing.itemGap))
                DetailField("Klient", task.clientName)
                Spacer(Modifier.height(JervisSpacing.itemGap))
                DetailField("Projekt", task.projectName)
                Spacer(Modifier.height(JervisSpacing.itemGap))
                DetailField("Naplánováno", formatInstant(task.task.scheduledAt))
                task.task.cronExpression?.let {
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    DetailField("Cron výraz", it)
                }
                task.task.correlationId?.let {
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    DetailField("Correlation ID", it)
                }
            }

            JSection(title = "Instrukce pro agenta") {
                Text(
                    text = task.task.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── Create Dialog ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTaskDialog(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onDismiss: () -> Unit,
    onCreate: (clientId: String, projectId: String?, taskName: String, content: String, cronExpression: String?) -> Unit,
) {
    var selectedClient by remember { mutableStateOf(clients.firstOrNull()) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var taskName by remember { mutableStateOf("") }
    var taskInstruction by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }
    var clientExpanded by remember { mutableStateOf(false) }
    var projectExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // Auto-select first project for selected client
    LaunchedEffect(selectedClient) {
        val clientProjects = projects.filter { it.clientId == selectedClient?.id }
        selectedProject = clientProjects.firstOrNull()
    }

    val clientProjects = projects.filter { it.clientId == selectedClient?.id }
    val enabled = selectedClient != null && taskInstruction.isNotBlank() && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naplánovat úlohu") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Client dropdown
                ExposedDropdownMenuBox(
                    expanded = clientExpanded,
                    onExpandedChange = { clientExpanded = !clientExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedClient?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Klient") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = clientExpanded,
                        onDismissRequest = { clientExpanded = false },
                    ) {
                        clients.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client.name) },
                                onClick = {
                                    selectedClient = client
                                    clientExpanded = false
                                },
                            )
                        }
                    }
                }

                // Project dropdown
                ExposedDropdownMenuBox(
                    expanded = projectExpanded,
                    onExpandedChange = { projectExpanded = !projectExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedProject?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Projekt (volitelné)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = projectExpanded,
                        onDismissRequest = { projectExpanded = false },
                    ) {
                        clientProjects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    selectedProject = project
                                    projectExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text("Název úlohy (volitelné)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = taskInstruction,
                    onValueChange = { taskInstruction = it },
                    label = { Text("Instrukce pro agenta") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )

                OutlinedTextField(
                    value = cronExpression,
                    onValueChange = { cronExpression = it },
                    label = { Text("Cron výraz (volitelné)") },
                    placeholder = { Text("např. 0 0 * * *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val client = selectedClient ?: return@Button
                    val finalName = taskName.ifBlank { "Úloha: ${taskInstruction.take(50)}" }
                    isSaving = true
                    onCreate(
                        client.id,
                        selectedProject?.id,
                        finalName,
                        taskInstruction,
                        cronExpression.ifBlank { null },
                    )
                },
                enabled = enabled,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Naplánovat")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────────

private fun formatInstant(epochMillis: Long): String {
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
