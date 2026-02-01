package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.PendingTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
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

    val taskTypes = remember { com.jervis.dto.TaskTypeEnum.values().map { it.name } }
    val taskStates = remember { com.jervis.dto.TaskStateEnum.values().map { it.name } }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching {
                tasks = repository.pendingTasks.listPendingTasks(selectedTaskType, selectedState)
                totalTasks = repository.pendingTasks.countPendingTasks(selectedTaskType, selectedState)
            }.onFailure { error = it.message }
            isLoading = false
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            try {
                repository.pendingTasks.deletePendingTask(taskId)
                infoMessage = "Úloha byla úspěšně smazána"
                load()
            } catch (t: Throwable) {
                infoMessage = "Smazání úlohy selhalo: ${t.message}"
            }
        }
    }

    LaunchedEffect(selectedTaskType, selectedState) { load() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Fronta úloh ($totalTasks)",
                onBack = onBack,
                actions = {
                    RefreshIconButton(onClick = { load() })
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            JSection(title = "Filtry") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterDropdown(
                        label = "Typ úlohy",
                        items = taskTypes,
                        selectedItem = selectedTaskType,
                        onItemSelected = { selectedTaskType = it },
                        modifier = Modifier.weight(1f)
                    )
                    FilterDropdown(
                        label = "Stav",
                        items = taskStates,
                        selectedItem = selectedState,
                        onItemSelected = { selectedState = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> {
                        JCenteredLoading()
                    }

                    error != null -> {
                        JErrorState(
                            message = "Chyba při načítání: $error",
                            onRetry = { load() },
                        )
                    }

                    tasks.isEmpty() -> {
                        JEmptyState(message = "Žádné čekající úlohy")
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(tasks) { task ->
                                JTableRowCard(selected = false) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(task.taskType, style = MaterialTheme.typography.titleMedium)
                                            DeleteIconButton(
                                                onClick = { pendingDeleteTaskId = task.id },
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Badge { Text(task.state) }
                                            task.projectId?.let {
                                                AssistChip(onClick = {}, label = { Text("Projekt: ${it.take(8)}") })
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Klient: ${task.clientId.take(8)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "Vytvořeno: ${task.createdAt}",
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
        ConfirmDialog(
            visible = pendingDeleteTaskId != null,
            title = "Smazat úlohu z fronty",
            message = "Opravdu chcete smazat tuto úlohu z fronty? Tuto akci nelze vrátit.",
            confirmText = "Smazat",
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
    onItemSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedItem ?: "Vše",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Vše") },
                onClick = {
                    onItemSelected(null)
                    expanded = false
                },
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    },
                )
            }
        }
    }
}
