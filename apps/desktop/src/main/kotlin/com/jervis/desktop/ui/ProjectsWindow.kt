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
                                    projectToDelete = project
                                    showDeleteDialog = true
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
            repository = repository,
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
                                errorMessage = "Failed to delete: ${e.message}"
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

    // Integration overrides state
    var showIntegrationSection by remember { mutableStateOf(false) }
    var jiraProjectKey by remember { mutableStateOf("") }
    var confluenceSpaceKey by remember { mutableStateOf("") }
    var confluenceRootPageId by remember { mutableStateOf("") }
    var availableJiraProjects by remember { mutableStateOf<List<com.jervis.dto.atlassian.AtlassianProjectRefDto>>(emptyList()) }
    var isLoadingIntegrations by remember { mutableStateOf(false) }
    var integrationMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Load integration status when project is being edited
    LaunchedEffect(project?.id, showIntegrationSection) {
        if (project != null && showIntegrationSection && !isLoadingIntegrations) {
            isLoadingIntegrations = true
            integrationMessage = null

            // Load project status
            runCatching {
                val status = repository.integrationSettings.getProjectStatus(project.id)
                jiraProjectKey = status.overrideJiraProjectKey ?: ""
                confluenceSpaceKey = status.overrideConfluenceSpaceKey ?: ""
                confluenceRootPageId = status.overrideConfluenceRootPageId ?: ""
            }.onFailure { e ->
                integrationMessage = "Failed to load project status: ${e.message}"
            }

            // Load available Jira projects
            if (clientId.isNotEmpty()) {
                runCatching {
                    availableJiraProjects = repository.atlassianSetup.listProjects(clientId)
                }.onFailure { e ->
                    integrationMessage = "Failed to load Jira projects: ${e.message}"
                }
            }

            isLoadingIntegrations = false
        }
    }

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

                // Integration Overrides Section (only for existing projects)
                if (project != null) {
                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Integration Overrides", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { showIntegrationSection = !showIntegrationSection }) {
                            Icon(
                                if (showIntegrationSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showIntegrationSection) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (showIntegrationSection) {
                        if (isLoadingIntegrations) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else {
                            Text(
                                "Map this project to specific Jira projects and Confluence spaces",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Jira Project Dropdown
                            Text("Jira Project", style = MaterialTheme.typography.labelMedium)

                            var jiraExpanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = jiraExpanded,
                                onExpandedChange = { jiraExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = if (jiraProjectKey.isBlank()) "None - use client default" else jiraProjectKey,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Jira Project (optional)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = jiraExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = jiraExpanded,
                                    onDismissRequest = { jiraExpanded = false }
                                ) {
                                    // Clear option
                                    DropdownMenuItem(
                                        text = { Text("None (use client default)") },
                                        onClick = {
                                            jiraProjectKey = ""
                                            jiraExpanded = false
                                        }
                                    )

                                    // Available projects
                                    availableJiraProjects.forEach { jiraProject ->
                                        DropdownMenuItem(
                                            text = { Text("${jiraProject.key}: ${jiraProject.name}") },
                                            onClick = {
                                                jiraProjectKey = jiraProject.key
                                                jiraExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Confluence Space
                            Text("Confluence Space", style = MaterialTheme.typography.labelMedium)
                            OutlinedTextField(
                                value = confluenceSpaceKey,
                                onValueChange = { confluenceSpaceKey = it },
                                label = { Text("Space Key (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("e.g., PROJ") },
                                supportingText = { Text("Leave empty to use client default") }
                            )

                            OutlinedTextField(
                                value = confluenceRootPageId,
                                onValueChange = { confluenceRootPageId = it },
                                label = { Text("Root Page ID (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("e.g., 123456789") },
                                supportingText = { Text("Starting page for project docs") }
                            )

                            if (integrationMessage != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (integrationMessage!!.contains("Failed")) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        }
                                    )
                                ) {
                                    Text(
                                        text = integrationMessage!!,
                                        modifier = Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
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

                    // Save integration overrides if project exists and section was opened
                    if (project != null && showIntegrationSection) {
                        scope.launch {
                            runCatching {
                                repository.integrationSettings.setProjectOverrides(
                                    com.jervis.dto.integration.ProjectIntegrationOverridesDto(
                                        projectId = project.id,
                                        jiraProjectKey = if (jiraProjectKey.isBlank()) "" else jiraProjectKey,
                                        confluenceSpaceKey = if (confluenceSpaceKey.isBlank()) "" else confluenceSpaceKey,
                                        confluenceRootPageId = if (confluenceRootPageId.isBlank()) "" else confluenceRootPageId
                                    )
                                )
                                integrationMessage = "Integration overrides saved successfully"
                            }.onFailure { e ->
                                integrationMessage = "Failed to save overrides: ${e.message}"
                            }
                        }
                    }
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
