package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderCapabilities
import com.jervis.dto.connection.ProviderEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.StatusIndicator
import com.jervis.ui.design.JPrimaryButton
import kotlinx.coroutines.launch
import com.jervis.ui.util.openUrlInBrowser

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

    // Function to reload connections
    suspend fun loadConnections() {
        try {
            connections = repository.connections.getAllConnections()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání připojení: ${e.message}")
        }
    }

    // Load connections and clients initially
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            connections = repository.connections.getAllConnections()
            clients = repository.clients.getAllClients()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                JPrimaryButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat připojení")
                }
            }

            if (connections.isEmpty() && isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
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
                                            if (result.success) "Test OK" else "Test selhal: ${result.message}",
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
                            },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }

    // Create Dialog
    if (showCreateDialog) {
        ConnectionCreateDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                scope.launch {
                    try {
                        val created = repository.connections.createConnection(request)
                        if (request.authType == AuthTypeEnum.OAUTH2) {
                            val authUrl = repository.connections.initiateOAuth2(created.id)
                            openUrlInBrowser(authUrl)
                            snackbarHostState.showSnackbar("OAuth2 autorizace byla spuštěna. Dokončete ji v prohlížeči.")
                        } else {
                            snackbarHostState.showSnackbar("Připojení vytvořeno")
                        }
                        loadConnections()
                        showCreateDialog = false
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
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
                        loadConnections()
                        showEditDialog = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
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
                                loadConnections()
                                showDeleteDialog = null
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Smazat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Zrušit")
                }
            },
        )
    }
}

@Composable
private fun ConnectionItemCard(
    connection: ConnectionResponseDto,
    clients: List<ClientDto>,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val assignedClient = clients.firstOrNull { it.connectionIds.contains(connection.id) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(connection.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text(connection.provider.name, style = MaterialTheme.typography.labelSmall) },
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusIndicator(connection.state.name)
                    }
                    Text(
                        text = connection.displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (assignedClient != null) {
                        Text(
                            text = "Používá: ${assignedClient.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row {
                    JPrimaryButton(onClick = onTest) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test")
                        Spacer(Modifier.width(4.dp))
                        Text("Test")
                    }
                    Spacer(Modifier.width(8.dp))
                    JPrimaryButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Upravit")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Smazat")
                    }
                }
            }

            // Capabilities row
            if (connection.capabilities.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Funkce: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    connection.capabilities.forEach { capability ->
                        Spacer(Modifier.width(4.dp))
                        CapabilityChip(capability)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(capability: ConnectionCapability) {
    val (label, color) =
        when (capability) {
            ConnectionCapability.BUGTRACKER -> "BugTracker" to MaterialTheme.colorScheme.error
            ConnectionCapability.WIKI -> "Wiki" to MaterialTheme.colorScheme.tertiary
            ConnectionCapability.REPOSITORY -> "Repo" to MaterialTheme.colorScheme.primary
            ConnectionCapability.EMAIL_READ -> "Email Read" to MaterialTheme.colorScheme.secondary
            ConnectionCapability.EMAIL_SEND -> "Email Send" to MaterialTheme.colorScheme.secondary
        }

    SuggestionChip(
        onClick = {},
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        },
    )
}

private val ConnectionResponseDto.displayUrl: String
    get() = baseUrl ?: host?.let { "$it${port?.let { port -> ":$port" } ?: ""}" } ?: "Bez adresy"

@Composable
private fun ConnectionCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    // Provider is the primary selector
    var provider by remember { mutableStateOf(ProviderEnum.GITHUB) }
    // Auth type determined by provider selection
    var authType by remember { mutableStateOf(AuthTypeEnum.OAUTH2) }
    // Common fields
    var baseUrl by remember { mutableStateOf("") }
    // Cloud flag for OAuth2 providers (GitHub, GitLab, Atlassian)
    var isCloud by remember { mutableStateOf(true) }
    // UI state
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedAuthType by remember { mutableStateOf(false) }

    // OAuth2 fields
    var authorizationUrl by remember { mutableStateOf("") }
    var tokenUrl by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("") }
    // Bearer token field
    var bearerToken by remember { mutableStateOf("") }
    // Basic auth fields
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Email fields
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("INBOX") }
    var useSsl by remember { mutableStateOf(true) }
    var useTls by remember { mutableStateOf(true) }

    // Determine if this is a DevOps or Email provider
    val isDevOpsProvider = ProviderCapabilities.isDevOpsProvider(provider)
    val isEmailProvider = ProviderCapabilities.isEmailProvider(provider)

    // Get available auth types for this provider
    val availableAuthTypes = ProviderCapabilities.authTypesForProvider(provider)
    val availableProtocols = ProviderCapabilities.protocolsForProvider(provider)

    // Protocol (derived from provider for DevOps, selectable for email)
    var protocol by remember { mutableStateOf(ProtocolEnum.HTTP) }
    var expandedProtocol by remember { mutableStateOf(false) }

    // Update protocol when provider changes
    LaunchedEffect(provider) {
        protocol = availableProtocols.firstOrNull() ?: ProtocolEnum.HTTP
        authType = availableAuthTypes.firstOrNull() ?: AuthTypeEnum.NONE
    }

    val enabled =
        name.isNotBlank() &&
            when {
                isEmailProvider -> {
                    host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                }
                isDevOpsProvider -> {
                    when (authType) {
                        AuthTypeEnum.OAUTH2 -> name.isNotBlank() && (isCloud || baseUrl.isNotBlank())
                        AuthTypeEnum.BEARER -> baseUrl.isNotBlank() && bearerToken.isNotBlank()
                        AuthTypeEnum.BASIC -> baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                        AuthTypeEnum.NONE -> baseUrl.isNotBlank()
                    }
                }
                else -> false
            }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vytvořit nové připojení") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                // Connection name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název připojení") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Provider dropdown (primary selector)
                ExposedDropdownMenuBox(
                    expanded = expandedProvider,
                    onExpandedChange = { expandedProvider = !expandedProvider },
                ) {
                    OutlinedTextField(
                        value = provider.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProvider) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expandedProvider,
                        onDismissRequest = { expandedProvider = false },
                    ) {
                        ProviderEnum.entries.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption.name) },
                                onClick = {
                                    provider = selectionOption
                                    expandedProvider = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Protocol dropdown (for email providers only)
                if (isEmailProvider && availableProtocols.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = expandedProtocol,
                        onExpandedChange = { expandedProtocol = !expandedProtocol },
                    ) {
                        OutlinedTextField(
                            value = protocol.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Protokol") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProtocol) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedProtocol,
                            onDismissRequest = { expandedProtocol = false },
                        ) {
                            availableProtocols.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption.name) },
                                    onClick = {
                                        protocol = selectionOption
                                        expandedProtocol = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Auth type dropdown (for DevOps providers with multiple auth options)
                if (isDevOpsProvider && availableAuthTypes.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = expandedAuthType,
                        onExpandedChange = { expandedAuthType = !expandedAuthType },
                    ) {
                        OutlinedTextField(
                            value = authType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Metoda autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuthType) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAuthType,
                            onDismissRequest = { expandedAuthType = false },
                        ) {
                            availableAuthTypes.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption.name) },
                                    onClick = {
                                        authType = selectionOption
                                        expandedAuthType = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cloud checkbox for OAuth2
                if (isDevOpsProvider && authType == AuthTypeEnum.OAUTH2) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = isCloud,
                            onCheckedChange = { isCloud = it },
                        )
                        Text("Cloud (veřejný GitHub/GitLab/Atlassian)", modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Base URL (for DevOps providers) - hidden for cloud OAuth2
                if (isDevOpsProvider && (authType != AuthTypeEnum.OAUTH2 || !isCloud)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Bearer token field
                if (isDevOpsProvider && authType == AuthTypeEnum.BEARER) {
                    OutlinedTextField(
                        value = bearerToken,
                        onValueChange = { bearerToken = it },
                        label = { Text("API Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Basic auth fields
                if ((isDevOpsProvider && authType == AuthTypeEnum.BASIC) || isEmailProvider) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (provider == ProviderEnum.ATLASSIAN) "Email" else "Uživatelské jméno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (provider == ProviderEnum.ATLASSIAN) "API Token" else "Heslo") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Email-specific fields
                if (isEmailProvider) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = useSsl,
                            onCheckedChange = { useSsl = it },
                        )
                        Text("Použít SSL", modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (protocol == ProtocolEnum.IMAP) {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            label = { Text("Název složky (volitelné)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionCreateRequestDto(
                        provider = provider,
                        protocol = protocol,
                        authType = authType,
                        name = name,
                        isCloud = isCloud,
                        baseUrl = if (isDevOpsProvider && (authType != AuthTypeEnum.OAUTH2 || !isCloud)) baseUrl else null,
                        username = if (authType == AuthTypeEnum.BASIC || isEmailProvider) username else null,
                        password = if (authType == AuthTypeEnum.BASIC || isEmailProvider) password else null,
                        bearerToken = if (authType == AuthTypeEnum.BEARER) bearerToken else null,
                        host = if (isEmailProvider) host else null,
                        port = if (isEmailProvider) port.toIntOrNull() else null,
                        useSsl = if (isEmailProvider) useSsl else null,
                        folderName = if (isEmailProvider && protocol == ProtocolEnum.IMAP) folderName else null,
                    )
                    onCreate(request)
                },
                enabled = enabled,
            ) {
                Text(if (authType == AuthTypeEnum.OAUTH2) "Authorizovat" else "Vytvořit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        },
    )
}

@Composable
private fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    onDismiss: () -> Unit,
    onSave: (String, ConnectionUpdateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf(connection.name) }
    var state by remember { mutableStateOf(connection.state) }
    var provider by remember { mutableStateOf(connection.provider) }
    var protocol by remember { mutableStateOf(connection.protocol) }
    var authType by remember { mutableStateOf(connection.authType) }

    // Common fields
    var baseUrl by remember { mutableStateOf(connection.baseUrl ?: "") }
    var isCloud by remember { mutableStateOf(connection.baseUrl.isNullOrEmpty()) }

    // UI state
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedAuthType by remember { mutableStateOf(false) }
    var expandedProtocol by remember { mutableStateOf(false) }

    // Bearer token
    var bearerToken by remember { mutableStateOf(connection.bearerToken ?: "") }
    // Basic auth
    var username by remember { mutableStateOf(connection.username ?: "") }
    var password by remember { mutableStateOf(connection.password ?: "") }
    // Email fields
    var host by remember { mutableStateOf(connection.host ?: "") }
    var port by remember { mutableStateOf(connection.port?.toString() ?: "") }
    var folderName by remember { mutableStateOf(connection.folderName ?: "INBOX") }
    var useSsl by remember { mutableStateOf(connection.useSsl ?: true) }

    // Determine if this is a DevOps or Email provider
    val isDevOpsProvider = ProviderCapabilities.isDevOpsProvider(provider)
    val isEmailProvider = ProviderCapabilities.isEmailProvider(provider)
    val availableAuthTypes = ProviderCapabilities.authTypesForProvider(provider)
    val availableProtocols = ProviderCapabilities.protocolsForProvider(provider)

    val enabled =
        name.isNotBlank() &&
            when {
                isEmailProvider -> {
                    host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                }
                isDevOpsProvider -> {
                    when (authType) {
                        AuthTypeEnum.OAUTH2 -> name.isNotBlank() && (isCloud || baseUrl.isNotBlank())
                        AuthTypeEnum.BEARER -> baseUrl.isNotBlank() && bearerToken.isNotBlank()
                        AuthTypeEnum.BASIC -> baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                        AuthTypeEnum.NONE -> baseUrl.isNotBlank()
                    }
                }
                else -> false
            }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit připojení") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                // Connection name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název připojení") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Provider dropdown (primary selector)
                ExposedDropdownMenuBox(
                    expanded = expandedProvider,
                    onExpandedChange = { expandedProvider = !expandedProvider },
                ) {
                    OutlinedTextField(
                        value = provider.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProvider) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expandedProvider,
                        onDismissRequest = { expandedProvider = false },
                    ) {
                        ProviderEnum.entries.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption.name) },
                                onClick = {
                                    provider = selectionOption
                                    expandedProvider = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Protocol dropdown (for email providers only)
                if (isEmailProvider && availableProtocols.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = expandedProtocol,
                        onExpandedChange = { expandedProtocol = !expandedProtocol },
                    ) {
                        OutlinedTextField(
                            value = protocol.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Protokol") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProtocol) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedProtocol,
                            onDismissRequest = { expandedProtocol = false },
                        ) {
                            availableProtocols.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption.name) },
                                    onClick = {
                                        protocol = selectionOption
                                        expandedProtocol = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Auth type dropdown (for DevOps providers with multiple auth options)
                if (isDevOpsProvider && availableAuthTypes.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = expandedAuthType,
                        onExpandedChange = { expandedAuthType = !expandedAuthType },
                    ) {
                        OutlinedTextField(
                            value = authType.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Metoda autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuthType) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAuthType,
                            onDismissRequest = { expandedAuthType = false },
                        ) {
                            availableAuthTypes.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption.name) },
                                    onClick = {
                                        authType = selectionOption
                                        expandedAuthType = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cloud checkbox for OAuth2
                if (isDevOpsProvider && authType == AuthTypeEnum.OAUTH2) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = isCloud,
                            onCheckedChange = { isCloud = it },
                        )
                        Text("Cloud (veřejný GitHub/GitLab/Atlassian)", modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Base URL (for DevOps providers) - hidden for cloud OAuth2
                if (isDevOpsProvider && (authType != AuthTypeEnum.OAUTH2 || !isCloud)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Bearer token field
                if (isDevOpsProvider && authType == AuthTypeEnum.BEARER) {
                    OutlinedTextField(
                        value = bearerToken,
                        onValueChange = { bearerToken = it },
                        label = { Text("API Token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Basic auth fields
                if ((isDevOpsProvider && authType == AuthTypeEnum.BASIC) || isEmailProvider) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (provider == ProviderEnum.ATLASSIAN) "Email" else "Uživatelské jméno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (provider == ProviderEnum.ATLASSIAN) "API Token" else "Heslo") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Email-specific fields
                if (isEmailProvider) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = useSsl,
                            onCheckedChange = { useSsl = it },
                        )
                        Text("Použít SSL", modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (protocol == ProtocolEnum.IMAP) {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            label = { Text("Název složky (volitelné)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = ConnectionUpdateRequestDto(
                        name = name,
                        provider = provider,
                        protocol = protocol,
                        authType = authType,
                        state = state,
                        isCloud = if (authType == AuthTypeEnum.OAUTH2) isCloud else null,
                        baseUrl = if (isDevOpsProvider && (authType != AuthTypeEnum.OAUTH2 || !isCloud)) baseUrl else null,
                        username = if (authType == AuthTypeEnum.BASIC || isEmailProvider) username else null,
                        password = if (authType == AuthTypeEnum.BASIC || isEmailProvider) password else null,
                        bearerToken = if (authType == AuthTypeEnum.BEARER) bearerToken else null,
                        host = if (isEmailProvider) host else null,
                        port = if (isEmailProvider) port.toIntOrNull() else null,
                        useSsl = if (isEmailProvider) useSsl else null,
                        folderName = if (isEmailProvider && protocol == ProtocolEnum.IMAP) folderName else null,
                    )
                    onSave(connection.id, request)
                },
                enabled = enabled,
            ) {
                Text("Uložit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        },
    )
}
