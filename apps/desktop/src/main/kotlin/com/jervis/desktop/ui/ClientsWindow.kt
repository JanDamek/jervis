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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientDialog(
    client: ClientDto?,
    repository: JervisRepository,
    onDismiss: () -> Unit,
    onSave: (ClientDto) -> Unit
) {
    var name by remember { mutableStateOf(client?.name ?: "") }
    var connections by remember { mutableStateOf<List<com.jervis.dto.connection.ConnectionResponseDto>>(emptyList()) }
    var selectedConnectionId by remember { mutableStateOf(client?.connectionIds?.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

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

                Text("Connection (optional):", style = MaterialTheme.typography.labelMedium)

                if (connections.isEmpty()) {
                    Text(
                        "No connections available. Create connections first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = connections.firstOrNull { it.id == selectedConnectionId }?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Connection") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    selectedConnectionId = null
                                    expanded = false
                                }
                            )
                            connections.forEach { conn ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(conn.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${conn.type} - ${conn.state.name}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedConnectionId = conn.id
                                        expanded = false
                                    }
                                )
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
                        connectionIds = listOfNotNull(selectedConnectionId),
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
}
