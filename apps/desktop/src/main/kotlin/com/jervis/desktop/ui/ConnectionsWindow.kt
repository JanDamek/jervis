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
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Connections Window - Connection Management
 * Create, edit, delete, and test connections (HTTP, IMAP, POP3, SMTP, OAuth2)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsWindow(repository: JervisRepository) {
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedConnection by remember { mutableStateOf<ConnectionResponseDto?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var connectionIdToDelete by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Load connections
    fun loadConnections() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                connections = repository.connections.listConnections()
            } catch (e: Exception) {
                errorMessage = "Failed to load connections: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) {
        loadConnections()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Management") },
                actions = {
                    IconButton(onClick = { loadConnections() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Add Connection")
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
                            onClick = { loadConnections() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                connections.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No connections yet")
                        TextButton(onClick = { showCreateDialog = true }) {
                            Text("Create your first connection")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections) { connection ->
                            ConnectionCard(
                                connection = connection,
                                onTest = {
                                    scope.launch {
                                        try {
                                            val result = repository.connections.testConnection(connection.id)
                                            testResult = if (result.success) {
                                                "✓ ${result.message}"
                                            } else {
                                                "✗ ${result.message}"
                                            }
                                        } catch (e: Exception) {
                                            testResult = "✗ Test failed: ${e.message}"
                                        }
                                    }
                                },
                                onEdit = {
                                    selectedConnection = connection
                                    showEditDialog = true
                                },
                                onDelete = {
                                    connectionIdToDelete = connection.id
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // Show test result as snackbar
            testResult?.let { result ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = {
                        TextButton(onClick = { testResult = null }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(result)
                }
            }
        }
    }

    // Create Dialog
    if (showCreateDialog) {
        ConnectionCreateDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                scope.launch {
                    try {
                        repository.connections.createConnection(request)
                        showCreateDialog = false
                        loadConnections()
                    } catch (e: Exception) {
                        errorMessage = "Failed to create: ${e.message}"
                    }
                }
            }
        )
    }

    // Edit Dialog
    if (showEditDialog && selectedConnection != null) {
        ConnectionEditDialog(
            connection = selectedConnection!!,
            onDismiss = {
                showEditDialog = false
                selectedConnection = null
            },
            onUpdate = { request ->
                scope.launch {
                    try {
                        repository.connections.updateConnection(selectedConnection!!.id, request)
                        showEditDialog = false
                        selectedConnection = null
                        loadConnections()
                    } catch (e: Exception) {
                        errorMessage = "Failed to update: ${e.message}"
                    }
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && connectionIdToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Connection") },
            text = { Text("Are you sure you want to delete this connection?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                repository.connections.deleteConnection(requireNotNull(connectionIdToDelete))
                                connectionIdToDelete = null
                                showDeleteDialog = false
                                loadConnections()
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
private fun ConnectionCard(
    connection: ConnectionResponseDto,
    onTest: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (connection.enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = connection.type,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                connection.baseUrl?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                connection.host?.let {
                    Text(
                        text = "$it:${connection.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (connection.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connection.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onTest) {
                    Icon(Icons.Default.PlayArrow, "Test Connection")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit Connection")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete")
                }
            }
        }
    }
}

@Composable
private fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    onDismiss: () -> Unit,
    onUpdate: (ConnectionUpdateRequestDto) -> Unit
) {
    var name by remember { mutableStateOf(connection.name) }
    var enabled by remember { mutableStateOf(connection.enabled) }

    // HTTP fields
    var baseUrl by remember { mutableStateOf(connection.baseUrl ?: "") }
    var credentials by remember { mutableStateOf("") }

    // Email fields
    var host by remember { mutableStateOf(connection.host ?: "") }
    var port by remember { mutableStateOf(connection.port?.toString() ?: "") }
    var username by remember { mutableStateOf(connection.username ?: "") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Connection: ${connection.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Type: ${connection.type}", style = MaterialTheme.typography.labelMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Type-specific fields
                when (connection.type) {
                    "HTTP" -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = credentials,
                            onValueChange = { credentials = it },
                            label = { Text("Credentials (optional, leave blank to keep)") },
                            placeholder = { Text("email@example.com:api_token") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "IMAP", "POP3", "SMTP" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password (optional, leave blank to keep)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionUpdateRequestDto(
                        name = name,
                        enabled = enabled,
                        baseUrl = if (connection.type == "HTTP" && baseUrl.isNotBlank()) baseUrl else null,
                        credentials = if (connection.type == "HTTP" && credentials.isNotBlank()) credentials else null,
                        host = if (connection.type != "HTTP" && host.isNotBlank()) host else null,
                        port = if (connection.type != "HTTP" && port.isNotBlank()) port.toIntOrNull() else null,
                        username = if (connection.type != "HTTP" && username.isNotBlank()) username else null,
                        password = if (connection.type != "HTTP" && password.isNotBlank()) password else null,
                    )
                    onUpdate(request)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConnectionCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit
) {
    var connectionType by remember { mutableStateOf("HTTP") }
    var name by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    // HTTP fields
    var baseUrl by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("NONE") }
    var credentials by remember { mutableStateOf("") }

    // Email fields
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useSsl by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Connection") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Connection type selector
                Text("Connection Type:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HTTP", "IMAP", "POP3", "SMTP").forEach { type ->
                        FilterChip(
                            selected = connectionType == type,
                            onClick = { connectionType = type },
                            label = { Text(type) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Type-specific fields
                when (connectionType) {
                    "HTTP" -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text("https://example.com") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = credentials,
                            onValueChange = { credentials = it },
                            label = { Text("Credentials (email:token)") },
                            placeholder = { Text("email@example.com:api_token") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "IMAP", "POP3", "SMTP" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            placeholder = { Text("imap.gmail.com") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            placeholder = { Text(if (connectionType == "SMTP") "587" else "993") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionCreateRequestDto(
                        type = connectionType,
                        name = name,
                        enabled = enabled,
                        baseUrl = if (connectionType == "HTTP") baseUrl else null,
                        authType = if (connectionType == "HTTP") authType else null,
                        credentials = if (connectionType == "HTTP") credentials.ifBlank { null } else null,
                        host = if (connectionType != "HTTP") host else null,
                        port = if (connectionType != "HTTP") port.toIntOrNull() else null,
                        username = if (connectionType != "HTTP") username else null,
                        password = if (connectionType != "HTTP") password.ifBlank { null } else null,
                        useSsl = if (connectionType != "HTTP") useSsl else null,
                    )
                    onCreate(request)
                },
                enabled = name.isNotBlank() && when (connectionType) {
                    "HTTP" -> baseUrl.isNotBlank()
                    else -> host.isNotBlank() && port.isNotBlank() && username.isNotBlank()
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
