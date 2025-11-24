package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Clients Window - Client Management
 * Create, edit, delete, and manage clients
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsWindow(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var clientIdToDelete by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Load clients
    fun loadClients() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                clients = repository.clients.listClients()
            } catch (e: Exception) {
                errorMessage = "Failed to load clients: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) {
        loadClients()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Client Management") },
                actions = {
                    IconButton(onClick = { loadClients() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Add Client")
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
                            onClick = { loadClients() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                clients.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No clients yet")
                        TextButton(onClick = { showCreateDialog = true }) {
                            Text("Create your first client")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(clients) { client ->
                            ClientCard(
                                client = client,
                                onEdit = { selectedClient = client },
                                onDelete = {
                                    clientIdToDelete = client.id
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
    if (showCreateDialog || selectedClient != null) {
        ClientDialog(
            client = selectedClient,
            repository = repository,
            onDismiss = {
                showCreateDialog = false
                selectedClient = null
            },
            onSave = { client ->
                scope.launch {
                    try {
                        if (client.id.isEmpty() || client.id == com.jervis.common.Constants.GLOBAL_ID_STRING) {
                            repository.clients.createClient(client)
                        } else {
                            repository.clients.updateClient(client.id, client)
                        }
                        showCreateDialog = false
                        selectedClient = null
                        loadClients()
                    } catch (e: Exception) {
                        errorMessage = "Failed to save: ${e.message}"
                    }
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && clientIdToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Client") },
            text = { Text("Are you sure you want to delete this client?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                repository.clients.deleteClient(requireNotNull(clientIdToDelete))
                                clientIdToDelete = null
                                showDeleteDialog = false
                                loadClients()
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
private fun ClientCard(
    client: ClientDto,
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
                    text = client.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "ID: ${client.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

@Composable
private fun ClientDialog(
    client: ClientDto?,
    repository: JervisRepository,
    onDismiss: () -> Unit,
    onSave: (ClientDto) -> Unit
) {
    var name by remember { mutableStateOf(client?.name ?: "") }
    var connections by remember { mutableStateOf<List<com.jervis.dto.connection.ConnectionResponseDto>>(emptyList()) }
    var selectedConnectionIds by remember { mutableStateOf(client?.connectionIds?.toSet() ?: emptySet()) }
    val connectionFilters = remember { mutableStateMapOf<String, com.jervis.dto.ConnectionFilterDto>() }
    var showFilterDialog by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Initialize filters from client
    LaunchedEffect(client) {
        connectionFilters.clear()
        client?.connectionFilters?.forEach {
            connectionFilters[it.connectionId] = it
        }
    }

    // Load connections
    LaunchedEffect(Unit) {
        try {
            connections = repository.connections.listConnections()
        } catch (e: Exception) {
            // Handle error
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (client == null) "Create Client" else "Edit Client") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Client Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Connections:", style = MaterialTheme.typography.labelMedium)

                if (connections.isEmpty()) {
                    Text(
                        "No connections available. Create connections first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(connections) { conn ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Checkbox(
                                        checked = selectedConnectionIds.contains(conn.id),
                                        onCheckedChange = { checked ->
                                            selectedConnectionIds = if (checked) {
                                                selectedConnectionIds + conn.id
                                            } else {
                                                connectionFilters.remove(conn.id)
                                                selectedConnectionIds - conn.id
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(conn.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            conn.type,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (selectedConnectionIds.contains(conn.id) &&
                                    conn.type == "HTTP" &&
                                    conn.baseUrl?.contains("atlassian.net") == true) {
                                    TextButton(onClick = { showFilterDialog = conn.id }) {
                                        Text("Filters", style = MaterialTheme.typography.labelSmall)
                                    }
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
                    val newClient = (client ?: ClientDto(name = "")).copy(
                        name = name,
                        connectionIds = selectedConnectionIds.toList(),
                        connectionFilters = connectionFilters.values.toList()
                    )
                    onSave(newClient)
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

    // Filter dialog for Atlassian connections
    showFilterDialog?.let { connId ->
        val conn = connections.firstOrNull { it.id == connId }
        if (conn != null) {
            AtlassianFilterDialog(
                connection = conn,
                filter = connectionFilters[connId],
                onDismiss = { showFilterDialog = null },
                onSave = { filter ->
                    connectionFilters[connId] = filter
                    showFilterDialog = null
                }
            )
        }
    }
}

@Composable
private fun AtlassianFilterDialog(
    connection: com.jervis.dto.connection.ConnectionResponseDto,
    filter: com.jervis.dto.ConnectionFilterDto?,
    onDismiss: () -> Unit,
    onSave: (com.jervis.dto.ConnectionFilterDto) -> Unit
) {
    var jiraProjects by remember { mutableStateOf(filter?.jiraProjects?.joinToString(", ") ?: "") }
    var confluenceSpaces by remember { mutableStateOf(filter?.confluenceSpaces?.joinToString(", ") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters for ${connection.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Specify which Jira projects and Confluence spaces to poll for this client.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = jiraProjects,
                    onValueChange = { jiraProjects = it },
                    label = { Text("Jira Projects") },
                    placeholder = { Text("e.g., PROJ, DEV, SUPPORT") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Comma-separated project keys") }
                )

                OutlinedTextField(
                    value = confluenceSpaces,
                    onValueChange = { confluenceSpaces = it },
                    label = { Text("Confluence Spaces") },
                    placeholder = { Text("e.g., DEV, SUPPORT, DOCS") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Comma-separated space keys") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val newFilter = com.jervis.dto.ConnectionFilterDto(
                    connectionId = connection.id,
                    jiraProjects = jiraProjects.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    confluenceSpaces = confluenceSpaces.split(",").map { it.trim() }.filter { it.isNotBlank() }
                )
                onSave(newFilter)
            }) {
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
