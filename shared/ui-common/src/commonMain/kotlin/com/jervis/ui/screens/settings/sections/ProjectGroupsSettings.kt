package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectGroupDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun ProjectGroupsSettings(repository: JervisRepository) {
    var groups by remember { mutableStateOf<List<ProjectGroupDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<ProjectGroupDto?>(null) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                groups = repository.projectGroups.getAllGroups()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    JListDetailLayout(
        items = groups,
        selectedItem = selectedGroup,
        isLoading = isLoading,
        onItemSelected = { selectedGroup = it },
        emptyMessage = "Žádné skupiny projektů",
        emptyIcon = "📂",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = { showNewGroupDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nová skupina")
                }
            }
        },
        listItem = { group ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedGroup = group },
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            group.description ?: "Bez popisu",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (group.resources.isNotEmpty()) {
                            val summary = group.resources.groupBy { it.capability }
                                .entries.joinToString(", ") { (cap, res) ->
                                    "${res.size}x ${getCapabilityLabel(cap)}"
                                }
                            Text(
                                summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        detailContent = { group ->
            ProjectGroupEditForm(
                group = group,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.projectGroups.updateGroup(updated.id, updated)
                            selectedGroup = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        try {
                            repository.projectGroups.deleteGroup(group.id)
                            selectedGroup = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onCancel = { selectedGroup = null },
            )
        },
    )

    if (showNewGroupDialog) {
        NewProjectGroupDialog(
            repository = repository,
            onCreated = {
                showNewGroupDialog = false
                loadData()
            },
            onDismiss = { showNewGroupDialog = false },
        )
    }
}

@Composable
private fun ProjectGroupEditForm(
    group: ProjectGroupDto,
    repository: JervisRepository,
    onSave: (ProjectGroupDto) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description ?: "") }

    // Resources model (same pattern as ProjectEditForm)
    var resources by remember { mutableStateOf(group.resources.toMutableList()) }
    var resourceLinks by remember { mutableStateOf(group.resourceLinks.toMutableList()) }

    // Client and connections
    var clientConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    var showAddResourceDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(group.clientId) {
        try {
            val client = repository.clients.getClientById(group.clientId) ?: return@LaunchedEffect
            val allConnections = repository.connections.getAllConnections()
            clientConnections = allConnections.filter { conn ->
                client.connectionIds.contains(conn.id)
            }
        } catch (_: Exception) {
        }
    }

    fun loadResourcesForCapability(connectionId: String, capability: ConnectionCapability) {
        val key = Pair(connectionId, capability)
        if (key in availableResources || key in loadingResources) return
        scope.launch {
            loadingResources = loadingResources + key
            try {
                val res = repository.connections.listAvailableResources(connectionId, capability)
                availableResources = availableResources + (key to res)
            } catch (_: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    LaunchedEffect(clientConnections) {
        clientConnections.forEach { conn ->
            conn.capabilities.forEach { cap ->
                loadResourcesForCapability(conn.id, cap)
            }
        }
    }

    fun addResource(connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) {
        if (resources.any { it.connectionId == connectionId && it.capability == capability && it.resourceIdentifier == resourceId }) return
        resources = (resources + ProjectResourceDto(
            connectionId = connectionId,
            capability = capability,
            resourceIdentifier = resourceId,
            displayName = displayName,
        )).toMutableList()
    }

    fun removeResource(res: ProjectResourceDto) {
        resources = resources.filter { it !== res }.toMutableList()
        resourceLinks = resourceLinks.filter { it.sourceId != res.id && it.targetId != res.id }.toMutableList()
    }

    JDetailScreen(
        title = group.name,
        onBack = onCancel,
        onSave = {
            onSave(
                group.copy(
                    name = name,
                    description = description.ifBlank { null },
                    resources = resources,
                    resourceLinks = resourceLinks,
                ),
            )
        },
        saveEnabled = name.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JSection(title = "Základní informace") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název skupiny") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(JervisSpacing.itemGap))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }

            JSection(title = "Sdílené zdroje skupiny") {
                Text(
                    "Zdroje na úrovni skupiny jsou sdílené všemi projekty ve skupině.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                if (resources.isEmpty()) {
                    Text(
                        "Žádné sdílené zdroje.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    resources.forEach { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .heightIn(min = JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        res.displayName.ifEmpty { res.resourceIdentifier },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${getCapabilityLabel(res.capability)} · ${res.resourceIdentifier}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { removeResource(res) },
                                    modifier = Modifier.size(JervisSpacing.touchTarget),
                                ) {
                                    Text("✕", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                JPrimaryButton(onClick = { showAddResourceDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Přidat zdroj")
                }
            }

            // Delete group
            JSection(title = "Nebezpečná zóna") {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                ) {
                    Text("Smazat skupinu")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Smazat skupinu?") },
            text = { Text("Projekty ve skupině nebudou smazány, pouze odřazeny ze skupiny.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Smazat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Zrušit")
                }
            },
        )
    }

    if (showAddResourceDialog) {
        // Reuse the same AddResourceDialog pattern from ProjectsSettings
        // For now, simplified version
        AddGroupResourceDialog(
            clientConnections = clientConnections,
            availableResources = availableResources,
            loadingResources = loadingResources,
            existingResources = resources,
            onAdd = { connectionId, capability, resourceId, displayName ->
                addResource(connectionId, capability, resourceId, displayName)
            },
            onDismiss = { showAddResourceDialog = false },
        )
    }
}

@Composable
private fun AddGroupResourceDialog(
    clientConnections: List<ConnectionResponseDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    existingResources: List<ProjectResourceDto>,
    onAdd: (connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filterText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat sdílený zdroj") },
        text = {
            Column {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text("Filtrovat...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    clientConnections.forEach { connection ->
                        connection.capabilities.forEach { capability ->
                            val key = Pair(connection.id, capability)
                            val allResources = availableResources[key] ?: emptyList()
                            val isLoading = key in loadingResources

                            val filteredResources = allResources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    filterText.isBlank() ||
                                        res.name.contains(filterText, ignoreCase = true) ||
                                        res.id.contains(filterText, ignoreCase = true)
                                }

                            if (isLoading) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp).heightIn(min = JervisSpacing.touchTarget),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${connection.name} · ${getCapabilityLabel(capability)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            } else if (filteredResources.isNotEmpty()) {
                                item {
                                    Text(
                                        "${connection.name} · ${getCapabilityLabel(capability)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(filteredResources) { resource ->
                                    val alreadyAdded = existingResources.any {
                                        it.connectionId == connection.id &&
                                            it.capability == capability &&
                                            it.resourceIdentifier == resource.id
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !alreadyAdded) {
                                                onAdd(connection.id, capability, resource.id, resource.name)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .heightIn(min = JervisSpacing.touchTarget),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                resource.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (alreadyAdded) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            Text(
                                                resource.id,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (alreadyAdded) {
                                            Text(
                                                "Přidáno",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        },
    )
}

@Composable
private fun NewProjectGroupDialog(
    repository: JervisRepository,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.getAllClients()
            if (clients.size == 1) {
                selectedClientId = clients.first().id
            }
        } catch (_: Exception) {
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nová skupina projektů") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název skupiny") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis (volitelné)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Client selector
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = clients.find { it.id == selectedClientId }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Klient") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        clients.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client.name) },
                                onClick = {
                                    selectedClientId = client.id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
                Button(
                    onClick = {
                        val clientId = selectedClientId ?: return@Button
                        isSaving = true
                        scope.launch {
                            try {
                                repository.projectGroups.saveGroup(
                                    ProjectGroupDto(
                                        clientId = clientId,
                                        name = name,
                                        description = description.ifBlank { null },
                                    ),
                                )
                                onCreated()
                            } catch (_: Exception) {
                                isSaving = false
                            }
                        }
                    },
                    enabled = name.isNotBlank() && selectedClientId != null && !isSaving,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Vytvořit")
                    }
                }
            }
        },
    )
}
