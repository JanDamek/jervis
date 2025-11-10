package com.jervis.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Placeholder windows - to be implemented with full functionality
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserTasksWindow(repository: JervisRepository) {
    var tasks by remember { mutableStateOf<List<com.jervis.dto.user.UserTaskDto>>(emptyList()) }
    var allTasks by remember { mutableStateOf<List<com.jervis.dto.user.UserTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterText by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<com.jervis.dto.user.UserTaskDto?>(null) }
    var instructionText by remember { mutableStateOf("") }
    var showRevokeConfirm by remember { mutableStateOf(false) }

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
                val allTasksList = mutableListOf<com.jervis.dto.user.UserTaskDto>()

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

    // Handle revoke
    fun handleRevoke() {
        val task = selectedTask ?: return
        showRevokeConfirm = true
    }

    fun confirmRevoke() {
        val task = selectedTask ?: return
        scope.launch {
            try {
                repository.userTasks.cancel(task.id)
                showRevokeConfirm = false
                selectedTask = null
                loadTasks()
            } catch (e: Exception) {
                errorMessage = "Failed to revoke task: ${e.message}"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Tasks") },
                actions = {
                    IconButton(onClick = { loadTasks() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
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
                leadingIcon = { Icon(Icons.Default.Search, "Search") },
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tasks (${tasks.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                            Button(
                                onClick = { handleRevoke() },
                                enabled = selectedTask != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Revoke")
                            }
                        }

                        HorizontalDivider()

                        when {
                            isLoading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            errorMessage != null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = errorMessage!!,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Button(
                                        onClick = { loadTasks() },
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                            tasks.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No tasks found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                                            onClick = { selectedTask = task }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Details and Quick Actions (right side)
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Task details
                    Card(
                        modifier = Modifier.weight(0.6f).fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Task Content",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                            HorizontalDivider()

                            if (selectedTask != null) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    item {
                                        TaskDetailField("Title", selectedTask!!.title)
                                        TaskDetailField("Priority", selectedTask!!.priority)
                                        TaskDetailField("Status", selectedTask!!.status)
                                        selectedTask!!.dueDateEpochMillis?.let {
                                            TaskDetailField("Due", formatDateTime(it))
                                        }
                                        TaskDetailField("Project", selectedTask!!.projectId ?: "-")
                                        TaskDetailField("Source", selectedTask!!.sourceType)

                                        if (!selectedTask!!.description.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Description:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = selectedTask!!.description!!,
                                                style = MaterialTheme.typography.bodyMedium
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

                    // Quick Actions
                    Card(
                        modifier = Modifier.weight(0.4f).fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Quick Actions",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                            HorizontalDivider()

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = instructionText,
                                    onValueChange = { instructionText = it },
                                    label = { Text("Instruction") },
                                    placeholder = { Text("Enter instructions for the agent...") },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    minLines = 4,
                                    maxLines = 8
                                )

                                Button(
                                    onClick = {
                                        val task = selectedTask
                                        if (task != null && instructionText.isNotBlank()) {
                                            scope.launch {
                                                try {
                                                    // Send instruction to agent orchestrator
                                                    repository.agentChat.sendUserTaskInstruction(
                                                        instruction = instructionText,
                                                        taskId = task.id,
                                                        clientId = task.clientId,
                                                        projectId = task.projectId ?: "",
                                                        wsSessionId = null
                                                    )
                                                    errorMessage = "Instruction sent to agent orchestrator. Monitor via notifications."
                                                    instructionText = ""
                                                } catch (e: Exception) {
                                                    errorMessage = "Failed to send instruction: ${e.message}"
                                                }
                                            }
                                        }
                                    },
                                    enabled = instructionText.isNotBlank() && selectedTask != null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Proceed")
                                }

                                if (instructionText.isBlank() || selectedTask == null) {
                                    Text(
                                        text = if (selectedTask == null) "Select a task first" else "Enter an instruction",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Revoke confirmation dialog
    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Confirm Revoke") },
            text = { Text("Discard selected task?") },
            confirmButton = {
                Button(
                    onClick = { confirmRevoke() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun UserTaskRow(
    task: com.jervis.dto.user.UserTaskDto,
    isSelected: Boolean,
    onClick: () -> Unit
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
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
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatDateTime(epochMillis: Long): String {
    return formatDate(epochMillis)
}

@Composable
fun ErrorLogsWindow(repository: JervisRepository) {
    ErrorLogsWindowContent(repository)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagSearchWindow(repository: JervisRepository) {
    var searchQuery by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<com.jervis.dto.ClientDto?>(null) }
    var selectedProject by remember { mutableStateOf<com.jervis.dto.ProjectDto?>(null) }
    var filterKey by remember { mutableStateOf("") }
    var filterValue by remember { mutableStateOf("") }
    var maxChunks by remember { mutableStateOf("100") }
    var minScore by remember { mutableStateOf("0.0") }

    var searchResults by remember { mutableStateOf<List<com.jervis.dto.rag.RagSearchItemDto>>(emptyList()) }
    var selectedResult by remember { mutableStateOf<com.jervis.dto.rag.RagSearchItemDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingData by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultCount by remember { mutableStateOf(0) }

    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var projectDropdownExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load clients and projects on mount
    LaunchedEffect(Unit) {
        isLoadingData = true
        try {
            clients = repository.clients.listClients()
            projects = repository.projects.getAllProjects()
            if (clients.isNotEmpty()) {
                selectedClient = clients[0]
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load data: ${e.message}"
        } finally {
            isLoadingData = false
        }
    }

    fun performSearch() {
        if (searchQuery.isBlank()) {
            errorMessage = "Please enter a search query"
            return
        }
        if (selectedClient == null) {
            errorMessage = "Please select a client"
            return
        }

        val maxChunksInt = maxChunks.toIntOrNull() ?: 20
        val minScoreDouble = minScore.toDoubleOrNull() ?: 0.15

        if (maxChunksInt !in 1..1000) {
            errorMessage = "Max Chunks must be between 1 and 1000"
            return
        }
        if (minScoreDouble !in 0.0..1.0) {
            errorMessage = "Min Score must be between 0.0 and 1.0"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val request = com.jervis.dto.rag.RagSearchRequestDto(
                    clientId = selectedClient!!.id,
                    projectId = selectedProject?.id,
                    searchText = searchQuery,
                    filterKey = filterKey.ifBlank { null },
                    filterValue = filterValue.ifBlank { null },
                    maxChunks = maxChunksInt,
                    minSimilarityThreshold = minScoreDouble
                )
                val response = repository.ragSearch.search(request)
                searchResults = response.items
                resultCount = response.items.size
                if (searchResults.isNotEmpty()) {
                    selectedResult = searchResults[0]
                }
            } catch (e: Exception) {
                errorMessage = "Search failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG Search${if (resultCount > 0) " - $resultCount results" else ""}") }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Left panel - Search form
            Column(
                modifier = Modifier.width(400.dp).fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Search Query", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Query") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                HorizontalDivider()

                Text("Filters", style = MaterialTheme.typography.titleSmall)

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
                        enabled = !isLoading && !isLoadingData
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
                        value = selectedProject?.name ?: "<All Projects>",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Project (Optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        enabled = !isLoading && !isLoadingData
                    )
                    ExposedDropdownMenu(
                        expanded = projectDropdownExpanded,
                        onDismissRequest = { projectDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("<All Projects>") },
                            onClick = {
                                selectedProject = null
                                projectDropdownExpanded = false
                            }
                        )
                        projects.forEach { project ->
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
                    value = filterKey,
                    onValueChange = { filterKey = it },
                    label = { Text("Filter Key (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = filterValue,
                    onValueChange = { filterValue = it },
                    label = { Text("Filter Value (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = maxChunks,
                    onValueChange = { maxChunks = it },
                    label = { Text("Max Chunks") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = minScore,
                    onValueChange = { minScore = it },
                    label = { Text("Min Score") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                Button(
                    onClick = { performSearch() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isLoadingData && searchQuery.isNotBlank() && selectedClient != null
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Search")
                }

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            VerticalDivider()

            // Right panel - Results and details
            Column(
                modifier = Modifier.fillMaxSize().weight(1f)
            ) {
                // Results list (top half)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(0.6f).padding(16.dp)
                ) {
                    Text(
                        "Results",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (searchResults.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No results yet. Enter a query and click Search.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults.size) { index ->
                                val result = searchResults[index]
                                val isSelected = result == selectedResult

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surface
                                    ),
                                    onClick = { selectedResult = result }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "Score: ${"%.4f".format(result.score)}",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                result.metadata["ragSourceType"] ?: "Unknown",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            result.content.take(150) + if (result.content.length > 150) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp),
                                            maxLines = 3
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Details panel (bottom half)
                Column(
                    modifier = Modifier.fillMaxWidth().weight(0.4f).padding(16.dp)
                ) {
                    Text(
                        "Details",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (selectedResult == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Select a result to view details",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val result = selectedResult!!
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()).padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "Score: ${"%.4f".format(result.score)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )

                                HorizontalDivider()

                                Text(
                                    "Metadata",
                                    style = MaterialTheme.typography.titleSmall
                                )

                                val sourceType = result.metadata["ragSourceType"] ?: "Unknown"
                                val sourceUri = result.metadata["sourceUri"] ?: ""
                                val createdAt = result.metadata["createdAt"] ?: ""
                                val chunkId = result.metadata["chunkId"]
                                val chunkOf = result.metadata["chunkOf"]

                                Text("Source Type: $sourceType", style = MaterialTheme.typography.bodySmall)
                                if (sourceUri.isNotEmpty()) {
                                    Text("URI: $sourceUri", style = MaterialTheme.typography.bodySmall)
                                }
                                if (createdAt.isNotEmpty()) {
                                    Text("Created: $createdAt", style = MaterialTheme.typography.bodySmall)
                                }
                                if (chunkId != null && chunkOf != null) {
                                    Text("Chunk: $chunkId of $chunkOf", style = MaterialTheme.typography.bodySmall)
                                }

                                val summary = result.metadata["summary"]
                                if (!summary.isNullOrBlank()) {
                                    HorizontalDivider()
                                    Text(
                                        "Summary",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(summary, style = MaterialTheme.typography.bodySmall)
                                }

                                HorizontalDivider()

                                Text(
                                    "Content",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(result.content, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerWindow(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var allProjects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<EnhancedScheduledTaskInfo>>(emptyList()) }

    var selectedClient by remember { mutableStateOf<com.jervis.dto.ClientDto?>(null) }
    var selectedProject by remember { mutableStateOf<com.jervis.dto.ProjectDto?>(null) }
    var selectedTask by remember { mutableStateOf<EnhancedScheduledTaskInfo?>(null) }

    var taskDescription by remember { mutableStateOf("") }
    var scheduleTime by remember { mutableStateOf("") }
    var isRepeatable by remember { mutableStateOf(false) }
    var cronExpression by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var isLoadingTasks by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready to create task") }
    var showError by remember { mutableStateOf(false) }

    var showTaskDetails by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var projectDropdownExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load scheduled tasks
    fun loadTasks() {
        scope.launch {
            isLoadingTasks = true
            try {
                val allTasks = repository.scheduledTasks.listAllTasks()

                // Enhance tasks with project and client names
                tasks = allTasks.map { task ->
                    val project = allProjects.find { it.id == task.projectId }
                    val client = project?.clientId?.let { clientId ->
                        clients.find { it.id == clientId }
                    }
                    EnhancedScheduledTaskInfo(
                        task = task,
                        projectName = project?.name ?: "Unknown Project",
                        clientName = client?.name
                    )
                }.sortedBy { it.task.scheduledAt }
            } catch (e: Exception) {
                statusMessage = "Error loading tasks: ${e.message}"
                showError = true
            } finally {
                isLoadingTasks = false
            }
        }
    }

    // Load clients and projects
    fun loadInitialData() {
        scope.launch {
            try {
                clients = repository.clients.listClients()
                allProjects = repository.projects.getAllProjects()

                if (clients.isNotEmpty()) {
                    selectedClient = clients[0]
                    projects = allProjects.filter { it.clientId == clients[0].id }
                    if (projects.isNotEmpty()) {
                        selectedProject = projects[0]
                    }
                }

                loadTasks()
            } catch (e: Exception) {
                statusMessage = "Error loading data: ${e.message}"
                showError = true
            }
        }
    }

    // Load projects for selected client
    fun loadProjectsForClient(clientId: String?) {
        if (clientId == null) return
        projects = allProjects.filter { it.clientId == clientId }
        selectedProject = if (projects.isNotEmpty()) projects[0] else null
    }

    // Validate task
    fun validateTask() {
        if (taskDescription.trim().length < 10 || !taskDescription.contains(" ")) {
            statusMessage = "Task description is too short or unclear - provide more details"
            showError = true
        } else {
            statusMessage = "Task validated and ready to schedule"
            showError = false
        }
    }

    // Schedule task
    fun scheduleTask() {
        val project = selectedProject
        if (project == null) {
            statusMessage = "Please select a project"
            showError = true
            return
        }

        if (taskDescription.trim().isEmpty()) {
            statusMessage = "Please enter task description"
            showError = true
            return
        }

        if (isRepeatable && cronExpression.trim().isEmpty()) {
            statusMessage = "Please enter cron expression for repeating task"
            showError = true
            return
        }

        scope.launch {
            isLoading = true
            statusMessage = "Creating task..."
            try {
                repository.scheduledTasks.scheduleTask(
                    projectId = project.id ?: return@launch,
                    taskName = "Task: ${taskDescription.take(50)}${if (taskDescription.length > 50) "..." else ""}",
                    taskInstruction = taskDescription,
                    cronExpression = if (isRepeatable) cronExpression else scheduleTime.ifEmpty { null },
                    priority = 0
                )

                statusMessage = "Task scheduled successfully"
                showError = false
                taskDescription = ""
                scheduleTime = ""
                isRepeatable = false
                cronExpression = ""
                loadTasks()
            } catch (e: Exception) {
                statusMessage = "Error scheduling task: ${e.message}"
                showError = true
            } finally {
                isLoading = false
            }
        }
    }

    // Execute task now
    fun executeTaskNow() {
        val client = selectedClient
        val project = selectedProject

        if (client == null || project == null) {
            statusMessage = "Please select client and project"
            showError = true
            return
        }

        if (taskDescription.trim().isEmpty()) {
            statusMessage = "Please enter task description"
            showError = true
            return
        }

        scope.launch {
            isLoading = true
            statusMessage = "Executing task..."
            try {
                repository.scheduledTasks.executeTaskNow(
                    taskInstruction = taskDescription,
                    clientId = client.id ?: return@launch,
                    projectId = project.id ?: return@launch,
                    wsSessionId = null
                )

                statusMessage = "Task sent for processing. Monitor via notifications."
                showError = false
                taskDescription = ""
            } catch (e: Exception) {
                statusMessage = "Error executing task: ${e.message}"
                showError = true
            } finally {
                isLoading = false
            }
        }
    }

    // Cancel task
    fun cancelTask(task: EnhancedScheduledTaskInfo) {
        scope.launch {
            try {
                repository.scheduledTasks.cancelTask(task.task.id)
                statusMessage = "Task cancelled successfully"
                showError = false
                selectedTask = null
                loadTasks()
            } catch (e: Exception) {
                statusMessage = "Error cancelling task: ${e.message}"
                showError = true
            }
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        loadInitialData()
    }

    // Update projects when client changes
    LaunchedEffect(selectedClient) {
        selectedClient?.id?.let { loadProjectsForClient(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task Scheduler") },
                actions = {
                    IconButton(onClick = { loadTasks() }) {
                        Icon(Icons.Default.Refresh, "Refresh tasks")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left panel - Task creation form
            Card(
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "New Task",
                        style = MaterialTheme.typography.titleLarge
                    )

                    HorizontalDivider()

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
                            enabled = !isLoading && projects.isNotEmpty()
                        )
                        ExposedDropdownMenu(
                            expanded = projectDropdownExpanded,
                            onDismissRequest = { projectDropdownExpanded = false }
                        ) {
                            projects.forEach { project ->
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

                    // Task description
                    OutlinedTextField(
                        value = taskDescription,
                        onValueChange = { taskDescription = it },
                        label = { Text("Task Description") },
                        placeholder = { Text("Describe the task to be executed...") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        minLines = 4,
                        maxLines = 6,
                        enabled = !isLoading
                    )

                    // Schedule time
                    OutlinedTextField(
                        value = scheduleTime,
                        onValueChange = { scheduleTime = it },
                        label = { Text("Schedule Time (Optional)") },
                        placeholder = { Text("e.g., dd.MM.yyyy HH:mm, 'tomorrow', 'in X hours'") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && !isRepeatable,
                        singleLine = true
                    )

                    // Repeatable checkbox
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isRepeatable,
                            onCheckedChange = {
                                isRepeatable = it
                                if (!it) cronExpression = ""
                            },
                            enabled = !isLoading
                        )
                        Text("Repeatable task")
                    }

                    // Cron expression
                    OutlinedTextField(
                        value = cronExpression,
                        onValueChange = { cronExpression = it },
                        label = { Text("Cron Expression") },
                        placeholder = { Text("e.g., '0 9 * * 1' = Every Monday at 9:00") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && isRepeatable,
                        singleLine = true
                    )

                    HorizontalDivider()

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { validateTask() },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && taskDescription.isNotBlank()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Validate")
                        }

                        Button(
                            onClick = { scheduleTask() },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && taskDescription.isNotBlank() && selectedProject != null
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Schedule")
                        }
                    }

                    Button(
                        onClick = { executeTaskNow() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && taskDescription.isNotBlank() &&
                                 selectedClient != null && selectedProject != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Execute Now")
                    }

                    // Status message
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (showError)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (showError)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Right panel - Scheduled tasks list
            Card(
                modifier = Modifier.weight(0.55f).fillMaxHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Scheduled Tasks (${tasks.size})",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    selectedTask?.let { showTaskDetails = true }
                                },
                                enabled = selectedTask != null
                            ) {
                                Icon(Icons.Default.Info, "View Details")
                            }

                            IconButton(
                                onClick = { showCancelConfirm = true },
                                enabled = selectedTask != null
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Cancel Task",
                                    tint = if (selectedTask != null)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

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
                                items(tasks) { taskInfo ->
                                    ScheduledTaskRow(
                                        taskInfo = taskInfo,
                                        isSelected = selectedTask?.task?.id == taskInfo.task.id,
                                        onClick = { selectedTask = taskInfo },
                                        onDoubleClick = {
                                            if (taskInfo.task.status == com.jervis.domain.task.ScheduledTaskStatusEnum.PENDING) {
                                                selectedTask = taskInfo
                                                // Execute selected task
                                                scope.launch {
                                                    try {
                                                        val project = allProjects.find { it.id == taskInfo.task.projectId }
                                                        if (project != null && project.clientId != null) {
                                                            repository.scheduledTasks.executeTaskNow(
                                                                taskInstruction = taskInfo.task.taskInstruction,
                                                                clientId = project.clientId!!,
                                                                projectId = project.id ?: return@launch,
                                                                wsSessionId = null
                                                            )
                                                            statusMessage = "Task '${taskInfo.task.taskName}' sent for processing"
                                                            showError = false
                                                            loadTasks()
                                                        }
                                                    } catch (e: Exception) {
                                                        statusMessage = "Error executing task: ${e.message}"
                                                        showError = true
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Task Details Dialog
    if (showTaskDetails && selectedTask != null) {
        val task = selectedTask!!.task
        AlertDialog(
            onDismissRequest = { showTaskDetails = false },
            title = { Text("Task Details") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TaskDetailRow("Name", task.taskName)
                    TaskDetailRow("Instruction", task.taskInstruction)
                    TaskDetailRow("Status", task.status.name)
                    TaskDetailRow("Scheduled At", formatDateTime(task.scheduledAt))
                    task.startedAt?.let { TaskDetailRow("Started At", formatDateTime(it)) }
                    task.completedAt?.let { TaskDetailRow("Completed At", formatDateTime(it)) }
                    TaskDetailRow("Created By", task.createdBy)
                    TaskDetailRow("Priority", task.priority.toString())
                    TaskDetailRow("Retries", "${task.retryCount}/${task.maxRetries}")
                    task.cronExpression?.let { TaskDetailRow("Cron Expression", it) }
                    task.errorMessage?.let { TaskDetailRow("Error", it) }
                    TaskDetailRow("Project", selectedTask!!.projectName)
                    selectedTask!!.clientName?.let { TaskDetailRow("Client", it) }
                }
            },
            confirmButton = {
                Button(onClick = { showTaskDetails = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Cancel Confirmation Dialog
    if (showCancelConfirm && selectedTask != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Confirm Cancellation") },
            text = { Text("Are you sure you want to cancel task '${selectedTask!!.task.taskName}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedTask?.let { cancelTask(it) }
                        showCancelConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel Task")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep Task")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledTaskRow(
    taskInfo: EnhancedScheduledTaskInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val task = taskInfo.task
    val statusColor = when (task.status) {
        com.jervis.domain.task.ScheduledTaskStatusEnum.PENDING -> MaterialTheme.colorScheme.primary
        com.jervis.domain.task.ScheduledTaskStatusEnum.RUNNING -> MaterialTheme.colorScheme.tertiary
        com.jervis.domain.task.ScheduledTaskStatusEnum.COMPLETED -> MaterialTheme.colorScheme.secondary
        com.jervis.domain.task.ScheduledTaskStatusEnum.FAILED -> MaterialTheme.colorScheme.error
        com.jervis.domain.task.ScheduledTaskStatusEnum.CANCELLED -> MaterialTheme.colorScheme.outline
    }

    val statusText = when (task.status) {
        com.jervis.domain.task.ScheduledTaskStatusEnum.PENDING -> "Pending"
        com.jervis.domain.task.ScheduledTaskStatusEnum.RUNNING -> "Running"
        com.jervis.domain.task.ScheduledTaskStatusEnum.COMPLETED -> "Completed"
        com.jervis.domain.task.ScheduledTaskStatusEnum.FAILED -> "Failed"
        com.jervis.domain.task.ScheduledTaskStatusEnum.CANCELLED -> "Cancelled"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick
            ),
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Badge(containerColor = statusColor) {
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (task.status == com.jervis.domain.task.ScheduledTaskStatusEnum.CANCELLED)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        if (task.cronExpression != null) {
                            Badge {
                                Text("Repeating", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = task.taskInstruction.take(80) +
                               if (task.taskInstruction.length > 80) "..." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    taskInfo.clientName?.let {
                        Text(
                            text = "Client: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Project: ${taskInfo.projectName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Due: ${formatDate(task.scheduledAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskDetailRow(label: String, value: String) {
    Column {
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

private data class EnhancedScheduledTaskInfo(
    val task: com.jervis.dto.ScheduledTaskDto,
    val projectName: String,
    val clientName: String?
)
