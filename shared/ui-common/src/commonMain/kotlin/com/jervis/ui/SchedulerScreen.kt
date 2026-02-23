package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.filterVisible
import com.jervis.dto.ScheduledTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Time-based groups for scheduled tasks display.
 */
private enum class TaskTimeGroup(val label: String) {
    OVERDUE("Prošlé"),
    TODAY("Dnes"),
    THIS_WEEK("Tento týden"),
    LATER("Později"),
    DONE("Dokončené"),
}

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

    fun buildEnhancedTasks(
        allTasks: List<ScheduledTaskDto>,
        clientMap: Map<String, ClientDto>,
        projectMap: Map<String, ProjectDto>,
    ): List<EnhancedScheduledTask> {
        val now = Clock.System.now().toEpochMilliseconds()
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val endOfWeek = today.plus(7, DateTimeUnit.DAY)

        return allTasks.map { task ->
            val client = clientMap[task.clientId]
            val project = task.projectId?.let { projectMap[it] }
            val group = classifyTimeGroup(task, now, today, endOfWeek, tz)
            EnhancedScheduledTask(
                task = task,
                projectName = project?.name ?: "Neznámý",
                clientName = client?.name ?: "Neznámý",
                timeGroup = group,
            )
        }.sortedWith(
            compareBy<EnhancedScheduledTask> { it.timeGroup.ordinal }
                .thenBy { it.task.scheduledAt },
        )
    }

    fun loadTasks() {
        scope.launch {
            isLoading = true
            try {
                val allTasks = repository.scheduledTasks.listAllTasks()
                val clientMap = clients.associateBy { it.id }
                val projectMap = projects.associateBy { it.id }
                tasks = buildEnhancedTasks(allTasks, clientMap, projectMap)
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
            // Parallelize RPC calls
            val clientsDeferred = async { repository.clients.getAllClients() }
            val projectsDeferred = async { repository.projects.getAllProjects().filterVisible() }
            val tasksDeferred = async { repository.scheduledTasks.listAllTasks() }

            clients = clientsDeferred.await()
            projects = projectsDeferred.await()
            val allTasks = tasksDeferred.await()

            val clientMap = clients.associateBy { it.id }
            val projectMap = projects.associateBy { it.id }
            tasks = buildEnhancedTasks(allTasks, clientMap, projectMap)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání dat: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    // Build grouped list items (group headers + tasks)
    val groupedItems = remember(tasks) {
        buildGroupedItems(tasks)
    }

    if (selectedTask != null) {
        ScheduledTaskDetail(
            task = selectedTask!!,
            onBack = { selectedTask = null },
            onDelete = { showDeleteConfirm = selectedTask },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                JTopBar(
                    title = "Kalendář úloh",
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
                Spacer(Modifier.height(JervisSpacing.itemGap))

                if (isLoading && tasks.isEmpty()) {
                    JCenteredLoading()
                } else if (tasks.isEmpty()) {
                    JEmptyState(message = "Žádné naplánované úlohy", icon = "📅")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(groupedItems.size) { index ->
                            when (val item = groupedItems[index]) {
                                is GroupedItem.Header -> {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (item.group == TaskTimeGroup.OVERDUE) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(
                                            start = 4.dp,
                                            top = if (index > 0) 8.dp else 0.dp,
                                        ),
                                    )
                                }
                                is GroupedItem.Task -> {
                                    ScheduledTaskListItem(
                                        task = item.task,
                                        onClick = { selectedTask = item.task },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            JSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
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

// --- Grouped list model ---

private sealed class GroupedItem {
    data class Header(val label: String, val group: TaskTimeGroup) : GroupedItem()
    data class Task(val task: EnhancedScheduledTask) : GroupedItem()
}

private fun buildGroupedItems(tasks: List<EnhancedScheduledTask>): List<GroupedItem> {
    if (tasks.isEmpty()) return emptyList()

    val result = mutableListOf<GroupedItem>()
    var currentGroup: TaskTimeGroup? = null

    for (task in tasks) {
        if (task.timeGroup != currentGroup) {
            currentGroup = task.timeGroup
            result.add(GroupedItem.Header(task.timeGroup.label, task.timeGroup))
        }
        result.add(GroupedItem.Task(task))
    }
    return result
}

// --- List item ---

@Composable
private fun ScheduledTaskListItem(
    task: EnhancedScheduledTask,
    onClick: () -> Unit,
) {
    val isOverdue = task.timeGroup == TaskTimeGroup.OVERDUE
    val isDone = task.timeGroup == TaskTimeGroup.DONE

    JCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isOverdue) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Prošlé",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.task.taskName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
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
                        color = if (isOverdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.task.cronExpression?.let { cron ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(cron, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    // State badge for non-NEW tasks
                    if (task.task.state != TaskStateEnum.NEW) {
                        val (badgeLabel, badgeColor) = taskStateBadge(task.task.state)
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    badgeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor,
                                )
                            },
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
}

// --- Helpers ---

/**
 * Classify a task into a time group based on its scheduledAt and state.
 */
private fun classifyTimeGroup(
    task: ScheduledTaskDto,
    nowMs: Long,
    today: LocalDate,
    endOfWeek: LocalDate,
    tz: TimeZone,
): TaskTimeGroup {
    // Terminal states go to DONE group
    if (task.state == TaskStateEnum.DONE || task.state == TaskStateEnum.ERROR) {
        return TaskTimeGroup.DONE
    }

    val taskDate = try {
        kotlinx.datetime.Instant.fromEpochMilliseconds(task.scheduledAt)
            .toLocalDateTime(tz).date
    } catch (_: Exception) {
        return TaskTimeGroup.LATER
    }

    return when {
        task.scheduledAt < nowMs -> TaskTimeGroup.OVERDUE
        taskDate == today -> TaskTimeGroup.TODAY
        taskDate < endOfWeek -> TaskTimeGroup.THIS_WEEK
        else -> TaskTimeGroup.LATER
    }
}

/**
 * Map task state to Czech label and color.
 */
@Composable
private fun taskStateBadge(state: TaskStateEnum): Pair<String, androidx.compose.ui.graphics.Color> {
    val color = when (state) {
        TaskStateEnum.DONE -> MaterialTheme.colorScheme.primary
        TaskStateEnum.ERROR -> MaterialTheme.colorScheme.error
        TaskStateEnum.QUALIFYING, TaskStateEnum.READY_FOR_QUALIFICATION -> MaterialTheme.colorScheme.tertiary
        TaskStateEnum.READY_FOR_GPU, TaskStateEnum.DISPATCHED_GPU,
        TaskStateEnum.PYTHON_ORCHESTRATING, TaskStateEnum.WAITING_FOR_AGENT,
        -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state) {
        TaskStateEnum.NEW -> "Nový"
        TaskStateEnum.READY_FOR_QUALIFICATION -> "Čeká na kvalifikaci"
        TaskStateEnum.QUALIFYING -> "Kvalifikuje se"
        TaskStateEnum.READY_FOR_GPU -> "Připraven"
        TaskStateEnum.DISPATCHED_GPU -> "Na GPU"
        TaskStateEnum.PYTHON_ORCHESTRATING -> "Zpracovává se"
        TaskStateEnum.WAITING_FOR_AGENT -> "Čeká na agenta"
        TaskStateEnum.USER_TASK -> "Čeká na uživatele"
        TaskStateEnum.DONE -> "Dokončeno"
        TaskStateEnum.ERROR -> "Chyba"
    }
    return label to color
}

data class EnhancedScheduledTask(
    val task: ScheduledTaskDto,
    val projectName: String,
    val clientName: String,
    val timeGroup: TaskTimeGroup = TaskTimeGroup.LATER,
)
