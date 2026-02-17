package com.jervis.ui.screens.settings.sections

import com.jervis.dto.filterVisible
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun ClientsSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var allProjects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<ClientDto?>(null) }
    var editingProject by remember { mutableStateOf<ProjectDto?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showArchivedSection by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun loadData() {
        isLoading = true
        try {
            clients = repository.clients.getAllClients()
            allProjects = repository.projects.getAllProjects().filterVisible()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    // If editing a client or project, show the edit form
    if (editingClient != null) {
        ClientEditForm(
            client = editingClient!!,
            repository = repository,
            onSave = { updated ->
                scope.launch {
                    try {
                        repository.clients.updateClient(updated.id, updated)
                        editingClient = null
                        loadData()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
            onCancel = { editingClient = null },
        )
        return
    }

    if (editingProject != null) {
        ProjectEditForm(
            project = editingProject!!,
            repository = repository,
            onSave = { updated ->
                scope.launch {
                    try {
                        repository.projects.saveProject(updated)
                        editingProject = null
                        loadData()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
            onCancel = { editingProject = null },
        )
        return
    }

    val activeClients = clients.filter { !it.archived }
    val archivedClients = clients.filter { it.archived }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            JActionBar {
                RefreshIconButton(onClick = {
                    scope.launch { loadData() }
                })
                JPrimaryButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat klienta")
                }
            }

            Spacer(Modifier.height(JervisSpacing.itemGap))

            if (clients.isEmpty() && isLoading) {
                JCenteredLoading()
            } else if (clients.isEmpty() && !isLoading) {
                JEmptyState(message = "Žádní klienti nenalezeni", icon = "🏢")
            } else {
                SelectionContainer {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(activeClients, key = { it.id }) { client ->
                            ClientExpandableCard(
                                client = client,
                                projects = allProjects.filter { it.clientId == client.id },
                                onEditClient = { editingClient = client },
                                onEditProject = { editingProject = it },
                                onCreateProject = { clientId ->
                                    scope.launch {
                                        try {
                                            repository.projects.saveProject(
                                                ProjectDto(name = "Nový projekt", clientId = clientId),
                                            )
                                            loadData()
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                        }
                                    }
                                },
                            )
                        }

                        // Archived section
                        if (archivedClients.isNotEmpty()) {
                            item {
                                JCard {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showArchivedSection = !showArchivedSection }
                                            .heightIn(min = JervisSpacing.touchTarget),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Archivovaní klienti (${archivedClients.size})",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = if (showArchivedSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    if (showArchivedSection) {
                                        HorizontalDivider()
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            archivedClients.forEach { client ->
                                                ClientExpandableCard(
                                                    client = client,
                                                    projects = allProjects.filter { it.clientId == client.id },
                                                    onEditClient = { editingClient = client },
                                                    onEditProject = { editingProject = it },
                                                    onCreateProject = { clientId ->
                                                        scope.launch {
                                                            try {
                                                                repository.projects.saveProject(
                                                                    ProjectDto(name = "Nový projekt", clientId = clientId),
                                                                )
                                                                loadData()
                                                            } catch (e: Exception) {
                                                                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                                            }
                                                        }
                                                    },
                                                )
                                                Spacer(Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }

    // Create client dialog
    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var newDescription by remember { mutableStateOf("") }

        JFormDialog(
            visible = true,
            title = "Vytvořit nového klienta",
            onConfirm = {
                scope.launch {
                    try {
                        repository.clients.createClient(
                            ClientDto(name = newName, description = newDescription.ifBlank { null }),
                        )
                        showCreateDialog = false
                        loadData()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
            onDismiss = { showCreateDialog = false },
            confirmEnabled = newName.isNotBlank(),
            confirmText = "Vytvořit",
        ) {
            JTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "Název klienta",
            )
            JTextField(
                value = newDescription,
                onValueChange = { newDescription = it },
                label = "Popis (volitelné)",
                singleLine = false,
                minLines = 2,
            )
        }
    }
}

@Composable
private fun ClientExpandableCard(
    client: ClientDto,
    projects: List<ProjectDto>,
    onEditClient: () -> Unit,
    onEditProject: (ProjectDto) -> Unit,
    onCreateProject: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    JCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium)
                client.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${projects.size} projektů",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            JIconButton(
                onClick = onEditClient,
                icon = Icons.Default.Edit,
                contentDescription = "Upravit klienta",
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            if (projects.isEmpty()) {
                Text(
                    "Žádné projekty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                projects.forEach { project ->
                    JCard {
                        Row(
                            modifier = Modifier
                                .heightIn(min = JervisSpacing.touchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.bodyMedium)
                                project.description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            JIconButton(
                                onClick = { onEditProject(project) },
                                icon = Icons.Default.Edit,
                                contentDescription = "Upravit projekt",
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            JPrimaryButton(onClick = { onCreateProject(client.id) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Nový projekt")
            }
        }
    }
}
