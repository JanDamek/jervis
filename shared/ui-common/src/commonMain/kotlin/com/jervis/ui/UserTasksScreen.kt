package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
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
        tasks =
            if (query.isBlank()) {
                allTasks
            } else {
                allTasks.filter { task ->
                    task.title.lowercase().contains(query) ||
                        (task.description?.lowercase()?.contains(query) == true) ||
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
                val clients = repository.clients.getAllClients()
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
                title = "Uživatelské úlohy",
                onBack = onBack,
                actions = {
                    RefreshIconButton(onClick = { loadTasks() })
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
        ) {
            // Filter field
            JSection {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text("Filtr") },
                    placeholder = { Text("Hledat podle názvu, popisu, zdroje nebo projektu...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main content area
            Row(
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Tasks list (left side)
                Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                    Text(
                        text = "Úlohy (${tasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    when {
                        isLoading -> {
                            JCenteredLoading()
                        }

                        errorMessage != null -> {
                            JErrorState(
                                message = errorMessage!!,
                                onRetry = { loadTasks() },
                            )
                        }

                        tasks.isEmpty() -> {
                            JEmptyState(message = "Žádné úlohy nenalezeny")
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(tasks) { task ->
                                    UserTaskRow(
                                        task = task,
                                        isSelected = selectedTask?.id == task.id,
                                        onClick = { selectedTask = task },
                                        onDelete = {
                                            selectedTask = task
                                            showDeleteConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Task details (right side)
                Column(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                    Text(
                        text = "Detaily úlohy",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    if (selectedTask != null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Scrollable details
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                JSection {
                                    TaskDetailField("Název", selectedTask!!.title)
                                    TaskDetailField("Stav", selectedTask!!.state)
                                    TaskDetailField("Projekt", selectedTask!!.projectId ?: "-")
                                }

                                if (!selectedTask!!.sourceUri.isNullOrBlank()) {
                                    JSection(title = "Odkaz na zdroj") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(selectedTask!!.sourceUri!!))
                                            }) {
                                                Text("Kopírovat")
                                            }
                                        }
                                        SelectionContainer {
                                            Text(
                                                text = selectedTask!!.sourceUri!!,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }

                                if (!selectedTask!!.description.isNullOrBlank()) {
                                    JSection(title = "Popis") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(selectedTask!!.description!!))
                                            }) {
                                                Text("Kopírovat")
                                            }
                                        }
                                        SelectionContainer {
                                            Text(
                                                text = selectedTask!!.description!!,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }

                            // Input area for additional instructions
                            Spacer(Modifier.height(16.dp))
                            JSection(title = "Dodatečné instrukce (volitelné)") {
                                OutlinedTextField(
                                    value = additionalInput,
                                    onValueChange = { additionalInput = it },
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    placeholder = { Text("Přidejte kontext nebo instrukce...") },
                                    maxLines = 4,
                                    enabled = !isSending,
                                )

                                Spacer(Modifier.height(8.dp))

                                // Direct send buttons
                                JActionBar {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isSending = true
                                                try {
                                                    repository.userTasks.sendToAgent(
                                                        selectedTask!!.id,
                                                        TaskRoutingMode.BACK_TO_PENDING,
                                                        additionalInput.takeIf { it.isNotBlank() },
                                                    )
                                                    additionalInput = ""
                                                    loadTasks()
                                                } catch (e: Exception) {
                                                    errorMessage = e.message ?: "Selhalo odeslání do fronty"
                                                } finally {
                                                    isSending = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isSending,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                            ),
                                    ) {
                                        Text("📋 Do fronty")
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isSending = true
                                                try {
                                                    repository.userTasks.sendToAgent(
                                                        selectedTask!!.id,
                                                        TaskRoutingMode.DIRECT_TO_AGENT,
                                                        additionalInput.takeIf { it.isNotBlank() },
                                                    )
                                                    additionalInput = ""
                                                    loadTasks()
                                                } catch (e: Exception) {
                                                    errorMessage = e.message ?: "Selhalo odeslání agentovi"
                                                } finally {
                                                    isSending = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isSending,
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                            ),
                                    ) {
                                        Text("⚡ Agentovi")
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Vyberte úlohu pro zobrazení detailů",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        title = "Smazat uživatelskou úlohu",
        message = "Opravdu chcete smazat tuto úlohu? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = { handleDelete() },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun UserTaskRow(
    task: UserTaskDto,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    JTableRowCard(
        selected = isSelected,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Badge { Text(task.state) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = task.sourceUri ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!task.projectId.isNullOrBlank()) {
                    Text(
                        text = "Projekt: ${task.projectId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            DeleteIconButton(onClick = onDelete)
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

private fun formatDate(epochMillis: Long): String {
    // Simple formatting - in real app use platform-specific date formatter
    return epochMillis.toString() // Placeholder
}

private fun formatDateTime(epochMillis: Long): String = formatDate(epochMillis)
