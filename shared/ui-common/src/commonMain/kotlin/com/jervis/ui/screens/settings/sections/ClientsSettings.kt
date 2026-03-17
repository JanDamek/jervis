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
import androidx.compose.material.icons.filled.CallMerge
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
    var mergeSource by remember { mutableStateOf<ProjectDto?>(null) }
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

    // Merge project dialog (top-level)
    if (mergeSource != null) {
        MergeProjectDialog(
            source = mergeSource!!,
            otherProjects = allProjects.filter {
                it.clientId == mergeSource!!.clientId && it.id != mergeSource!!.id
            },
            repository = repository,
            onDismiss = { mergeSource = null },
            onMerged = { src, tgt ->
                mergeSource = null
                scope.launch {
                    snackbarHostState.showSnackbar("Projekt ${src.name} spojen do ${tgt.name}")
                    loadData()
                }
            },
            onError = { msg ->
                mergeSource = null
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
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
                                onMergeProject = { source -> mergeSource = source },
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
    onMergeProject: (source: ProjectDto) -> Unit = { },
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
                            if (projects.size > 1) {
                                JIconButton(
                                    onClick = { onMergeProject(project) },
                                    icon = Icons.Default.CallMerge,
                                    contentDescription = "Spojit projekt",
                                )
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

/**
 * 2-step merge dialog: select target → preview conflicts → resolve → execute.
 */
@Composable
private fun MergeProjectDialog(
    source: ProjectDto,
    otherProjects: List<ProjectDto>,
    repository: JervisRepository,
    onDismiss: () -> Unit,
    onMerged: (source: ProjectDto, target: ProjectDto) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTarget by remember { mutableStateOf<ProjectDto?>(null) }
    var preview by remember { mutableStateOf<com.jervis.dto.MergePreviewDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var resolutions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var customValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val step = if (preview != null) "conflicts" else "select"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (step == "select") "Spojit projekt" else "Konflikty ke spojeni")
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                if (step == "select") {
                    Text(
                        "Presunout data z ${source.name} do jineho projektu a smazat puvodni.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text("Cilovy projekt:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    otherProjects.forEach { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTarget = target }
                                .heightIn(min = JervisSpacing.touchTarget)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedTarget?.id == target.id,
                                onClick = { selectedTarget = target },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(target.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (preview != null) {
                    // Show auto-migrate summary
                    if (preview!!.autoMigrate.isNotEmpty()) {
                        val totalDocs = preview!!.autoMigrate.sumOf { it.count }
                        Text(
                            "Automaticky presunuto: $totalDocs dokumentu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    if (preview!!.conflicts.isEmpty()) {
                        Text(
                            "Zadne konflikty. Vse bude presunuto automaticky.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "${preview!!.conflicts.size} konfliktu k vyreseni:",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        preview!!.conflicts.forEach { conflict ->
                            JCard {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(conflict.label, style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(4.dp))
                                    val currentRes = resolutions[conflict.key] ?: "KEEP_TARGET"
                                    // Source option
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { resolutions = resolutions + (conflict.key to "KEEP_SOURCE") }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = currentRes == "KEEP_SOURCE",
                                            onClick = { resolutions = resolutions + (conflict.key to "KEEP_SOURCE") },
                                        )
                                        Text(
                                            "A: ${conflict.sourceValue.take(80)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                    }
                                    // Target option
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { resolutions = resolutions + (conflict.key to "KEEP_TARGET") }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = currentRes == "KEEP_TARGET",
                                            onClick = { resolutions = resolutions + (conflict.key to "KEEP_TARGET") },
                                        )
                                        Text(
                                            "B: ${conflict.targetValue.take(80)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                    }
                                    // Editable merge option — pre-filled with AI suggestion
                                    if (true) { // All conflicts shown in dialog are TEXT (settings/resources auto-merge)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    resolutions = resolutions + (conflict.key to "CUSTOM")
                                                    if (conflict.key !in customValues && conflict.aiMergedValue != null) {
                                                        customValues = customValues + (conflict.key to conflict.aiMergedValue!!)
                                                    }
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            androidx.compose.material3.RadioButton(
                                                selected = currentRes == "CUSTOM",
                                                onClick = {
                                                    resolutions = resolutions + (conflict.key to "CUSTOM")
                                                    if (conflict.key !in customValues && conflict.aiMergedValue != null) {
                                                        customValues = customValues + (conflict.key to conflict.aiMergedValue!!)
                                                    }
                                                },
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    if (conflict.aiMergedValue != null) "Upravit (AI navrh):" else "Vlastni:",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                if (currentRes == "CUSTOM") {
                                                    Spacer(Modifier.height(4.dp))
                                                    androidx.compose.material3.OutlinedTextField(
                                                        value = customValues[conflict.key]
                                                            ?: conflict.aiMergedValue
                                                            ?: "",
                                                        onValueChange = {
                                                            customValues = customValues + (conflict.key to it)
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        textStyle = MaterialTheme.typography.bodySmall,
                                                        minLines = 2,
                                                        maxLines = 6,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // (Resources and settings auto-merge — no UI needed)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                if (isLoading) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (step == "select") {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val tgt = selectedTarget ?: return@TextButton
                        isLoading = true
                        scope.launch {
                            try {
                                preview = repository.projects.previewMerge(source.id, tgt.id)
                                // Default all resolutions to KEEP_TARGET
                                resolutions = preview!!.conflicts.associate { it.key to "KEEP_TARGET" }
                            } catch (e: Exception) {
                                onError("Chyba: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = selectedTarget != null && !isLoading,
                ) {
                    Text("Dalsi")
                }
            } else {
                androidx.compose.material3.TextButton(
                    onClick = {
                        isLoading = true
                        scope.launch {
                            try {
                                val req = com.jervis.dto.MergeExecuteDto(
                                    sourceProjectId = source.id,
                                    targetProjectId = selectedTarget!!.id,
                                    resolutions = resolutions.map { (k, v) ->
                                        com.jervis.dto.MergeResolutionDto(
                                            key = k,
                                            resolution = v,
                                            customValue = if (v == "CUSTOM") customValues[k] else null,
                                        )
                                    },
                                )
                                repository.projects.executeMerge(req)
                                onMerged(source, selectedTarget!!)
                            } catch (e: Exception) {
                                onError("Chyba pri spojovani: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Spojit a smazat")
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Zrusit")
            }
        },
    )
}
