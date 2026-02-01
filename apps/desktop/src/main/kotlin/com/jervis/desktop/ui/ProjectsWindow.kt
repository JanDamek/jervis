package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalFocusManager
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.dto.GitConfigDto
import com.jervis.dto.ProjectDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Projects Window - Project Management
 * Create, edit, delete, and manage projects
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsWindow(repository: JervisRepository) {
    val focusManager = LocalFocusManager.current
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<ProjectDto?>(null) }

    val scope = rememberCoroutineScope()

    // Load projects
    fun loadProjects() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                projects = repository.projects.getAllProjects()
            } catch (e: Exception) {
            errorMessage = "Failed to load projects: ${e.message}"
            repository.errorLogs.recordUiError(
                message = errorMessage!!,
                stackTrace = e.toString(),
                causeType = e::class.simpleName
            )
            } finally {
                isLoading = false
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) {
        loadProjects()
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                true
            } else false
        },
        topBar = {
            TopAppBar(
                title = { Text("Project Management") },
                actions = {
                    IconButton(onClick = { loadProjects() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Add Project")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = { loadProjects() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                projects.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No projects yet")
                        TextButton(onClick = { showCreateDialog = true }) {
                            Text("Create your first project")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(projects) { project ->
                            ProjectCard(
                                project = project,
                                onEdit = { selectedProject = project },
                                onDelete = {
                                    projectToDelete = project
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create/Edit Dialog
    if (showCreateDialog || selectedProject != null) {
        ProjectDialog(
            project = selectedProject,
            repository = repository,
            onDismiss = {
                showCreateDialog = false
                selectedProject = null
            },
            onSave = { project ->
                scope.launch {
                    try {
                        if (selectedProject == null) {
                            repository.projects.saveProject(project)
                        } else {
                            repository.projects.updateProject(project)
                        }
                        showCreateDialog = false
                        selectedProject = null
                        loadProjects()
                    } catch (e: Exception) {
                        val msg = "Failed to save: ${e.message}"
                        errorMessage = msg
                        repository.errorLogs.recordUiError(
                            message = msg,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName
                        )
                    }
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && projectToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Project") },
            text = { Text("Are you sure you want to delete this project?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                repository.projects.deleteProject(requireNotNull(projectToDelete))
                                projectToDelete = null
                                showDeleteDialog = false
                                loadProjects()
                            } catch (e: Exception) {
                                val msg = "Failed to delete: ${e.message}"
                                errorMessage = msg
                                repository.errorLogs.recordUiError(
                                    message = msg,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName
                                )
                                showDeleteDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name ?: "Unnamed Project",
                    style = MaterialTheme.typography.titleMedium
                )
                if (project.clientId != null) {
                    Text(
                        text = "Client: ${project.clientId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDialog(
    project: ProjectDto?,
    repository: JervisRepository,
    onDismiss: () -> Unit,
    onSave: (ProjectDto) -> Unit
) {
    var name by remember { mutableStateOf(project?.name ?: "") }
    var clientId by remember { mutableStateOf(project?.clientId ?: "") }
    var description by remember { mutableStateOf(project?.description ?: "") }

    // Resource identifiers from client's connections
    var gitRepositoryConnectionId by remember { mutableStateOf(project?.gitRepositoryConnectionId ?: "") }
    var gitRepositoryIdentifier by remember { mutableStateOf(project?.gitRepositoryIdentifier ?: "") }
    var jiraProjectConnectionId by remember { mutableStateOf(project?.jiraProjectConnectionId ?: "") }
    var jiraProjectKey by remember { mutableStateOf(project?.jiraProjectKey ?: "") }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "Create Project" else "Edit Project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Basic Info Section
                Text("Basic Information", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = project == null // Don't allow changing client after creation
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                HorizontalDivider()
                Text("Resources from Client Connections", style = MaterialTheme.typography.titleSmall)

                Text("Git Repository", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = gitRepositoryConnectionId,
                    onValueChange = { gitRepositoryConnectionId = it },
                    label = { Text("Connection ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gitRepositoryIdentifier,
                    onValueChange = { gitRepositoryIdentifier = it },
                    label = { Text("Repository identifier (e.g., owner/repo)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text("Jira Project (optional)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = jiraProjectConnectionId,
                    onValueChange = { jiraProjectConnectionId = it },
                    label = { Text("Connection ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = jiraProjectKey,
                    onValueChange = { jiraProjectKey = it },
                    label = { Text("Jira Project Key") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newProject = (project ?: ProjectDto(name = "", clientId = null)).copy(
                        name = name,
                        clientId = clientId,
                        description = description.ifBlank { null },
                        gitRepositoryConnectionId = gitRepositoryConnectionId.ifBlank { null },
                        gitRepositoryIdentifier = gitRepositoryIdentifier.ifBlank { null },
                        jiraProjectConnectionId = jiraProjectConnectionId.ifBlank { null },
                        jiraProjectKey = jiraProjectKey.ifBlank { null }
                    )
                    onSave(newProject)
                },
                enabled = name.isNotBlank() && clientId.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
