package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.StatusIndicator
import kotlinx.coroutines.launch

@Composable
fun ConnectionsSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Dialog states
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }
    var showDeleteDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                connections = repository.connections.listConnections()
                clients = repository.clients.listClients()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { loadData() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Načíst")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat připojení")
                }
            }

            if (isLoading && connections.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(connections) { connection ->
                        ConnectionItemCard(
                            connection = connection,
                            clients = clients,
                            onTest = {
                                scope.launch {
                                    try {
                                        val result = repository.connections.testConnection(connection.id)
                                        snackbarHostState.showSnackbar(
                                            if (result.success) "Test OK" else "Test selhal: ${result.message}"
                                        )
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba testu: ${e.message}")
                                    }
                                }
                            },
                            onEdit = {
                                showEditDialog = connection
                            },
                            onDelete = {
                                showDeleteDialog = connection
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )
    }
    
    // Create Dialog
    if (showCreateDialog) {
        ConnectionCreateDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                scope.launch {
                    try {
                        repository.connections.createConnection(request)
                        snackbarHostState.showSnackbar("Připojení vytvořeno")
                        loadData()
                        showCreateDialog = false
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            }
        )
    }
    
    // Edit Dialog
    showEditDialog?.let { connection ->
        ConnectionEditDialog(
            connection = connection,
            onDismiss = { showEditDialog = null },
            onSave = { id, request ->
                scope.launch {
                    try {
                        repository.connections.updateConnection(id, request)
                        snackbarHostState.showSnackbar("Připojení aktualizováno")
                        loadData()
                        showEditDialog = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            }
        )
    }
    
    // Delete Dialog
    showDeleteDialog?.let { connection ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Smazat připojení") },
            text = { Text("Opravdu chcete smazat připojení '${connection.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                repository.connections.deleteConnection(connection.id)
                                snackbarHostState.showSnackbar("Připojení smazáno")
                                loadData()
                                showDeleteDialog = null
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Smazat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Zrušit")
                }
            }
        )
    }
}

@Composable
private fun ConnectionItemCard(
    connection: ConnectionResponseDto,
    clients: List<ClientDto>,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val assignedClient = clients.firstOrNull { it.connectionIds.contains(connection.id) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(connection.type, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Text(
                    text = connection.baseUrl ?: connection.host ?: "Bez adresy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assignedClient != null) {
                    Text(
                        text = "Používá: ${assignedClient.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            StatusIndicator(connection.state.name)
            
            Row(modifier = Modifier.padding(start = 16.dp)) {
                Button(onClick = onTest) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Test")
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Upravit")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Smazat")
                }
            }
        }
    }
}

@Composable
private fun ConnectionCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("HTTP") }
    var baseUrl by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("NONE") }
    var httpBearerToken by remember { mutableStateOf("") }
    var httpBasicUsername by remember { mutableStateOf("") }
    var httpBasicPassword by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("") }
    var expandedType by remember { mutableStateOf(false) }
    var expandedAuth by remember { mutableStateOf(false) }
    
    val connectionTypes = listOf("HTTP", "OAUTH2")
    val authTypes = listOf("NONE", "BEARER", "BASIC")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vytvořit nové připojení") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název připojení") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = it }
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Typ připojení") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        connectionTypes.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { 
                                    type = option
                                    expandedType = false
                                }
                            )
                        }
                    }
                }
                
                if (type == "HTTP" || type == "OAUTH2") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.github.com nebo https://gitlab.com/api/v4") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (type == "HTTP") {
                    // Auth type dropdown
                    ExposedDropdownMenuBox(
                        expanded = expandedAuth,
                        onExpandedChange = { expandedAuth = it }
                    ) {
                        OutlinedTextField(
                            value = authType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Typ autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuth) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAuth,
                            onDismissRequest = { expandedAuth = false }
                        ) {
                            authTypes.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = { 
                                        authType = option
                                        expandedAuth = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (authType == "BEARER") {
                        OutlinedTextField(
                            value = httpBearerToken,
                            onValueChange = { httpBearerToken = it },
                            label = { Text("Bearer Token") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    if (authType == "BASIC") {
                        OutlinedTextField(
                            value = httpBasicUsername,
                            onValueChange = { httpBasicUsername = it },
                            label = { Text("Uživatelské jméno") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = httpBasicPassword,
                            onValueChange = { httpBasicPassword = it },
                            label = { Text("Heslo") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                if (type == "OAUTH2") {
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text("Client Secret") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = scope,
                        onValueChange = { scope = it },
                        label = { Text("Scope (volitelné)") },
                        placeholder = { Text("např. repo,read:user") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionCreateRequestDto(
                        type = type,
                        name = name,
                        state = ConnectionStateEnum.NEW,
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        authType = if (type == "HTTP") authType else null,
                        httpBearerToken = if (authType == "BEARER") httpBearerToken.takeIf { it.isNotBlank() } else null,
                        httpBasicUsername = if (authType == "BASIC") httpBasicUsername.takeIf { it.isNotBlank() } else null,
                        httpBasicPassword = if (authType == "BASIC") httpBasicPassword.takeIf { it.isNotBlank() } else null,
                        clientSecret = if (type == "OAUTH2") clientSecret.takeIf { it.isNotBlank() } else null,
                        scope = if (type == "OAUTH2") scope.takeIf { it.isNotBlank() } else null
                    )
                    onCreate(request)
                },
                enabled = name.isNotBlank() && (type != "HTTP" && type != "OAUTH2" || baseUrl.isNotBlank())
            ) {
                Text("Vytvořit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}

@Composable
private fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    onDismiss: () -> Unit,
    onSave: (String, ConnectionUpdateRequestDto) -> Unit
) {
    var name by remember { mutableStateOf(connection.name) }
    var baseUrl by remember { mutableStateOf(connection.baseUrl ?: "") }
    var authType by remember { mutableStateOf(connection.authType ?: "NONE") }
    var httpBearerToken by remember { mutableStateOf("") }
    var httpBasicUsername by remember { mutableStateOf(connection.httpBasicUsername ?: "") }
    var httpBasicPassword by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var expandedAuth by remember { mutableStateOf(false) }
    
    val authTypes = listOf("NONE", "BEARER", "BASIC")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit připojení") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název připojení") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (connection.type == "HTTP" || connection.type == "OAUTH2") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (connection.type == "HTTP") {
                    // Auth type dropdown
                    ExposedDropdownMenuBox(
                        expanded = expandedAuth,
                        onExpandedChange = { expandedAuth = it }
                    ) {
                        OutlinedTextField(
                            value = authType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Typ autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuth) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAuth,
                            onDismissRequest = { expandedAuth = false }
                        ) {
                            authTypes.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = { 
                                        authType = option
                                        expandedAuth = false
                                    }
                                )
                            }
                        }
                    }
                    
                    if (authType == "BEARER") {
                        OutlinedTextField(
                            value = httpBearerToken,
                            onValueChange = { httpBearerToken = it },
                            label = { Text("Bearer Token (nechte prázdné pro zachování existujícího)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    if (authType == "BASIC") {
                        OutlinedTextField(
                            value = httpBasicUsername,
                            onValueChange = { httpBasicUsername = it },
                            label = { Text("Uživatelské jméno") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = httpBasicPassword,
                            onValueChange = { httpBasicPassword = it },
                            label = { Text("Heslo (nechte prázdné pro zachování existujícího)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                if (connection.type == "OAUTH2") {
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text("Client Secret (nechte prázdné pro zachování existujícího)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionUpdateRequestDto(
                        name = name.takeIf { it.isNotBlank() },
                        baseUrl = baseUrl.takeIf { it.isNotBlank() },
                        authType = if (connection.type == "HTTP") authType else null,
                        httpBearerToken = if (authType == "BEARER" && httpBearerToken.isNotBlank()) httpBearerToken else null,
                        httpBasicUsername = if (authType == "BASIC" && httpBasicUsername.isNotBlank()) httpBasicUsername else null,
                        httpBasicPassword = if (authType == "BASIC" && httpBasicPassword.isNotBlank()) httpBasicPassword else null,
                        clientSecret = if (connection.type == "OAUTH2" && clientSecret.isNotBlank()) clientSecret else null
                    )
                    onSave(connection.id, request)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Uložit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}
