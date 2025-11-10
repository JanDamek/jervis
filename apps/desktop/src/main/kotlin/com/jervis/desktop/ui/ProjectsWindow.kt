package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }

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
                                    scope.launch {
                                        try {
                                            repository.projects.deleteProject(project)
                                            loadProjects()
                                        } catch (e: Exception) {
                                            errorMessage = "Failed to delete: ${e.message}"
                                        }
                                    }
                                },
                                onSetDefault = {
                                    scope.launch {
                                        try {
                                            repository.projects.setDefaultProject(project)
                                            loadProjects()
                                        } catch (e: Exception) {
                                            errorMessage = "Failed to set default: ${e.message}"
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

    // Create/Edit Dialog
    if (showCreateDialog || selectedProject != null) {
        ProjectDialog(
            project = selectedProject,
            onDismiss = {
                showCreateDialog = false
                selectedProject = null
            },
            onSave = { project ->
                scope.launch {
                    try {
                        repository.projects.saveProject(project)
                        showCreateDialog = false
                        selectedProject = null
                        loadProjects()
                    } catch (e: Exception) {
                        errorMessage = "Failed to save: ${e.message}"
                    }
                }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
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
                IconButton(onClick = onSetDefault) {
                    Icon(Icons.Default.Star, "Set as Default")
                }
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

@Composable
private fun ProjectDialog(
    project: ProjectDto?,
    onDismiss: () -> Unit,
    onSave: (ProjectDto) -> Unit
) {
    var name by remember { mutableStateOf(project?.name ?: "") }
    var clientId by remember { mutableStateOf(project?.clientId ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "Create Project" else "Edit Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newProject = (project ?: ProjectDto(name = "", clientId = null)).copy(
                        name = name,
                        clientId = clientId
                    )
                    onSave(newProject)
                },
                enabled = name.isNotBlank()
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
