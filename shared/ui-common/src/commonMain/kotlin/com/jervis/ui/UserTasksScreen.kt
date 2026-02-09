package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.RefreshIconButton
import com.jervis.ui.util.rememberClipboardManager
import kotlinx.coroutines.launch

@Composable
fun UserTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    val clipboard = rememberClipboardManager()
    var allTasks by remember { mutableStateOf<List<UserTaskDto>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<UserTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterText by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<UserTaskDto?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<UserTaskDto?>(null) }

    val scope = rememberCoroutineScope()

    fun applyFilter() {
        val query = filterText.trim().lowercase()
        tasks = if (query.isBlank()) {
            allTasks
        } else {
            allTasks.filter { task ->
                task.title.lowercase().contains(query) ||
                    (task.description?.lowercase()?.contains(query) == true) ||
                    (task.projectId?.lowercase()?.contains(query) == true)
            }
        }
    }

    fun loadTasks() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val clients = repository.clients.getAllClients()
                val allTasksList = mutableListOf<UserTaskDto>()

                for (client in clients) {
                    try {
                        client.id?.let { clientId ->
                            val clientTasks = repository.userTasks.listActive(clientId)
                            allTasksList.addAll(clientTasks)
                        }
                    } catch (_: Exception) {
                        // Continue loading other clients' tasks even if one fails
                    }
                }

                allTasks = allTasksList.sortedBy { it.createdAtEpochMillis }
                applyFilter()
            } catch (e: Exception) {
                errorMessage = "Chyba načítání úloh: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun handleDelete() {
        val task = taskToDelete ?: return
        scope.launch {
            try {
                repository.userTasks.cancel(task.id)
                showDeleteConfirm = false
                taskToDelete = null
                if (selectedTask?.id == task.id) selectedTask = null
                loadTasks()
            } catch (e: Exception) {
                errorMessage = "Chyba mazání úlohy: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { loadTasks() }

    LaunchedEffect(filterText) { applyFilter() }

    if (errorMessage != null && selectedTask == null) {
        Column {
            JTopBar(title = "Uživatelské úlohy", onBack = onBack)
            JErrorState(message = errorMessage!!, onRetry = { loadTasks() })
        }
    } else {
        JListDetailLayout(
            items = tasks,
            selectedItem = selectedTask,
            isLoading = isLoading,
            onItemSelected = { selectedTask = it },
            emptyMessage = "Žádné úlohy nenalezeny",
            emptyIcon = "📋",
            listHeader = {
                JTopBar(title = "Uživatelské úlohy", onBack = onBack, actions = {
                    RefreshIconButton(onClick = { loadTasks() })
                })

                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text("Filtr") },
                    placeholder = { Text("Hledat podle názvu, popisu nebo projektu...") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
                    singleLine = true,
                )
            },
            listItem = { task ->
                UserTaskRow(
                    task = task,
                    onClick = { selectedTask = task },
                    onDelete = {
                        taskToDelete = task
                        showDeleteConfirm = true
                    },
                )
            },
            detailContent = { task ->
                UserTaskDetail(
                    task = task,
                    repository = repository,
                    clipboard = clipboard,
                    onBack = { selectedTask = null },
                    onTaskSent = {
                        selectedTask = null
                        loadTasks()
                    },
                    onError = { errorMessage = it },
                    onDelete = {
                        taskToDelete = task
                        showDeleteConfirm = true
                    },
                )
            },
        )
    }

    ConfirmDialog(
        visible = showDeleteConfirm && taskToDelete != null,
        title = "Smazat uživatelskou úlohu",
        message = "Opravdu chcete smazat úlohu \"${taskToDelete?.title}\"? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = { handleDelete() },
        onDismiss = { showDeleteConfirm = false },
        isDestructive = true,
    )
}

@Composable
private fun UserTaskRow(
    task: UserTaskDto,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Badge { Text(task.state) }
                    if (!task.projectId.isNullOrBlank()) {
                        Text(
                            text = task.projectId!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            DeleteIconButton(onClick = onDelete)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun UserTaskDetail(
    task: UserTaskDto,
    repository: JervisRepository,
    clipboard: com.jervis.ui.util.ClipboardHandler,
    onBack: () -> Unit,
    onTaskSent: () -> Unit,
    onError: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var additionalInput by remember(task.id) { mutableStateOf("") }
    var isSending by remember(task.id) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun sendToAgent(mode: TaskRoutingMode) {
        scope.launch {
            isSending = true
            try {
                repository.userTasks.sendToAgent(
                    task.id,
                    mode,
                    additionalInput.takeIf { it.isNotBlank() },
                )
                onTaskSent()
            } catch (e: Exception) {
                onError(e.message ?: "Selhalo odeslání úlohy")
            } finally {
                isSending = false
            }
        }
    }

    JDetailScreen(
        title = task.title,
        onBack = onBack,
        actions = {
            DeleteIconButton(onClick = onDelete)
        },
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JSection(title = "Základní údaje") {
                TaskDetailField("Stav", task.state)
                TaskDetailField("Projekt", task.projectId ?: "-")
                TaskDetailField("Klient", task.clientId)
            }

            if (!task.sourceUri.isNullOrBlank()) {
                JSection(title = "Odkaz na zdroj") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(task.sourceUri!!))
                        }) {
                            Text("Kopírovat")
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = task.sourceUri!!,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (!task.description.isNullOrBlank()) {
                JSection(title = "Popis") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(task.description!!))
                        }) {
                            Text("Kopírovat")
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = task.description!!,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            JSection(title = "Dodatečné instrukce (volitelné)") {
                OutlinedTextField(
                    value = additionalInput,
                    onValueChange = { additionalInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Přidejte kontext nebo instrukce...") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isSending,
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // Action buttons at the bottom
        JActionBar(modifier = Modifier.padding(vertical = JervisSpacing.outerPadding)) {
            Button(
                onClick = { sendToAgent(TaskRoutingMode.BACK_TO_PENDING) },
                enabled = !isSending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Text("Do fronty")
            }
            Button(
                onClick = { sendToAgent(TaskRoutingMode.DIRECT_TO_AGENT) },
                enabled = !isSending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Agentovi")
            }
        }
    }
}

@Composable
private fun TaskDetailField(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
