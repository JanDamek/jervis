package com.jervis.ui

import androidx.compose.foundation.layout.*
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
                JCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedTask = task },
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

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd),
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
                            scheduledAtEpochMs = null,
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
