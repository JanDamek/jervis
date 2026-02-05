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
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTypeEnum
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.HttpAuthTypeEnum
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
                        if (request.type == ConnectionTypeEnum.OAUTH2) {
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
                            label = { Text(connection.type.name, style = MaterialTheme.typography.labelSmall) },
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
            ConnectionCapability.EMAIL -> "Email" to MaterialTheme.colorScheme.secondary
            ConnectionCapability.GIT -> "Git" to MaterialTheme.colorScheme.primary
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
    // Auth method for non-email providers
    var authMethod by remember { mutableStateOf("OAUTH2") }
    // Common fields
    var baseUrl by remember { mutableStateOf("") }
    // Cloud flag for OAuth2 providers (GitHub, GitLab, Atlassian)
    var isCloud by remember { mutableStateOf(true) }
    // OAuth2 fields
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedGitAuthMethod by remember { mutableStateOf(false) }
    var expandedState by remember { mutableStateOf(false) }

    var authorizationUrl by remember { mutableStateOf("") }
    var tokenUrl by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("") }
    // API Token field (for HTTP)
    var httpBearerToken by remember { mutableStateOf("") }
    // Email fields (IMAP, POP3, SMTP)
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("INBOX") }
    var useSsl by remember { mutableStateOf(true) }
    var useTls by remember { mutableStateOf(true) }
    // Basic Auth fields for Atlassian API token
    var httpBasicUsername by remember { mutableStateOf("") }
    var httpBasicPassword by remember { mutableStateOf("") }

    val enabled =
        name.isNotBlank() &&
            when (provider) {
                ProviderEnum.IMAP -> {
                    host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                }

                else -> {
                    if (authMethod == "OAUTH2") {
                        // For OAuth2 cloud: only name required; for on-premise: name and baseUrl required
                        name.isNotBlank() && (isCloud || baseUrl.isNotBlank())
                    } else {
                        // API TOKEN
                        if (provider == ProviderEnum.ATLASSIAN) {
                            baseUrl.isNotBlank() && httpBasicUsername.isNotBlank() && httpBasicPassword.isNotBlank()
                        } else {
                            baseUrl.isNotBlank() && httpBearerToken.isNotBlank()
                        }
                    }
                }
            }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Obecné", "Autentizace")

    val providerOptions = ProviderEnum.entries
    val authMethodOptions = listOf("OAUTH2", "API TOKEN")
    val isOAuth2 = provider != ProviderEnum.IMAP && authMethod == "OAUTH2"

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
                        value = provider?.name ?: "",
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
                        providerOptions.forEach { selectionOption ->
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

                // Auth method dropdown (only for non-email providers)
                if (provider != ProviderEnum.IMAP) {
                    ExposedDropdownMenuBox(
                        expanded = expandedGitAuthMethod,
                        onExpandedChange = { expandedGitAuthMethod = !expandedGitAuthMethod },
                    ) {
                        OutlinedTextField(
                            value = authMethod,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Metoda autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGitAuthMethod) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedGitAuthMethod,
                            onDismissRequest = { expandedGitAuthMethod = false },
                        ) {
                            authMethodOptions.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption) },
                                    onClick = {
                                        authMethod = selectionOption
                                        expandedGitAuthMethod = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cloud checkbox for OAuth2
                if (provider != ProviderEnum.IMAP && authMethod == "OAUTH2") {
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

                // Base URL (for GITHUB, GITLAB, ATLASSIAN) - hidden for cloud OAuth2
                if (provider != ProviderEnum.IMAP && (authMethod != "OAUTH2" || !isCloud)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }


                // API Token or Basic Auth field (for non-email providers with API TOKEN)
                if (provider != ProviderEnum.IMAP && authMethod == "API TOKEN") {
                    if (provider == ProviderEnum.ATLASSIAN) {
                        // Show basic auth fields for Atlassian
                        OutlinedTextField(
                            value = httpBasicUsername,
                            onValueChange = { httpBasicUsername = it },
                            label = { Text("Uživatelské jméno (email)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = httpBasicPassword,
                            onValueChange = { httpBasicPassword = it },
                            label = { Text("API Token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Show bearer token for GitHub/GitLab
                        OutlinedTextField(
                            value = httpBearerToken,
                            onValueChange = { httpBearerToken = it },
                            label = { Text("API Token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Email fields (for IMAP)
                if (provider == ProviderEnum.IMAP) {
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

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Uživatelské jméno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Heslo") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val request =
                        when (provider) {
                            ProviderEnum.IMAP -> {
                                ConnectionCreateRequestDto(
                                    type = ConnectionTypeEnum.IMAP,
                                    provider = provider,
                                    name = name,
                                    host = host,
                                    port = port.toIntOrNull(),
                                    username = username,
                                    password = password,
                                    useSsl = useSsl,
                                    folderName = folderName,
                                )
                            }

                            else -> {
                                if (isOAuth2) {
                                    // OAuth2 connections: only name, provider, isCloud; credentials managed by server
                                    ConnectionCreateRequestDto(
                                        type = ConnectionTypeEnum.OAUTH2,
                                        provider = provider,
                                        name = name,
                                        isCloud = isCloud,
                                        baseUrl = if (isCloud) null else baseUrl,
                                        gitProvider = provider.name,
                                    )
                                } else {
                                    if (provider == ProviderEnum.ATLASSIAN) {
                                        ConnectionCreateRequestDto(
                                            type = ConnectionTypeEnum.HTTP,
                                            provider = provider,
                                            name = name,
                                            baseUrl = baseUrl,
                                            authType = HttpAuthTypeEnum.BASIC,
                                            httpBasicUsername = httpBasicUsername,
                                            httpBasicPassword = httpBasicPassword,
                                            gitProvider = provider.name,
                                        )
                                    } else {
                                        ConnectionCreateRequestDto(
                                            type = ConnectionTypeEnum.HTTP,
                                            provider = provider,
                                            name = name,
                                            baseUrl = baseUrl,
                                            authType = HttpAuthTypeEnum.BEARER,
                                            httpBearerToken = httpBearerToken,
                                            gitProvider = provider.name,
                                        )
                                    }
                                }
                            }
                        }
                    onCreate(request)
                },
                enabled = enabled,
            ) {
                Text(if (isOAuth2) "Authorizovat" else "Vytvořit")
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
    // Provider and auth method
    var provider by remember { mutableStateOf<ProviderEnum?>(null) }
    var authMethod by remember { mutableStateOf("OAUTH2") }
    // Common fields
    var baseUrl by remember { mutableStateOf("") }
    // Cloud flag for OAuth2 providers
    var isCloud by remember { mutableStateOf(false) }
    // OAuth2 fields
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedGitAuthMethod by remember { mutableStateOf(false) }
    var expandedState by remember { mutableStateOf(false) }

    var authorizationUrl by remember { mutableStateOf("") }
    var tokenUrl by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("") }
    // API Token
    var httpBearerToken by remember { mutableStateOf("") }
    // Basic Auth fields for Atlassian API token
    var httpBasicUsername by remember { mutableStateOf("") }
    var httpBasicPassword by remember { mutableStateOf("") }
    // Email fields
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("INBOX") }
    var useSsl by remember { mutableStateOf(true) }
    var useTls by remember { mutableStateOf(true) }

    // Initialize from connection
    LaunchedEffect(connection) {
        when (connection.type) {
            ConnectionTypeEnum.IMAP -> {
                provider = connection.provider
                authMethod = "IMAP"
                host = connection.host ?: ""
                port = connection.port?.toString() ?: ""
                username = connection.username ?: ""
                password = connection.password ?: ""
                useSsl = connection.useSsl ?: true
                folderName = connection.folderName ?: "INBOX"
            }

            ConnectionTypeEnum.OAUTH2 -> {
                provider = connection.provider
                authMethod = "OAUTH2"
                baseUrl = connection.baseUrl ?: ""
                isCloud = connection.baseUrl.isNullOrEmpty()
                authorizationUrl = connection.authorizationUrl ?: ""
                tokenUrl = connection.tokenUrl ?: ""
                clientSecret = connection.clientSecret ?: ""
                redirectUri = connection.redirectUri ?: ""
                scope = connection.scope ?: ""
            }

            ConnectionTypeEnum.HTTP -> {
                val base = connection.baseUrl ?: ""
                provider = connection.provider
                // Determine authMethod based on authType and fields
                when (connection.authType) {
                    HttpAuthTypeEnum.BASIC -> {
                        if (provider == ProviderEnum.ATLASSIAN) {
                            authMethod = "API TOKEN"
                            httpBasicUsername = connection.httpBasicUsername ?: ""
                            httpBasicPassword = connection.httpBasicPassword ?: ""
                        } else {
                            authMethod = "API TOKEN"
                        }
                    }

                    HttpAuthTypeEnum.BEARER -> {
                        authMethod = "API TOKEN"
                        httpBearerToken = connection.httpBearerToken ?: ""
                    }

                    else -> {
                        // Fallback: check fields
                        if (connection.httpBearerToken?.isNotBlank() == true) {
                            authMethod = "API TOKEN"
                            httpBearerToken = connection.httpBearerToken ?: ""
                        } else if (connection.authorizationUrl?.isNotBlank() == true || connection.tokenUrl?.isNotBlank() == true) {
                            authMethod = "OAUTH2"
                            authorizationUrl = connection.authorizationUrl ?: ""
                            tokenUrl = connection.tokenUrl ?: ""
                            clientSecret = connection.clientSecret ?: ""
                            redirectUri = connection.redirectUri ?: ""
                            scope = connection.scope ?: ""
                        } else {
                            authMethod = "API TOKEN"
                        }
                    }
                }
                baseUrl = base
            }

            else -> {
                provider = connection.provider
                authMethod = "OAUTH2"
            }
        }
    }

    val enabled =
        name.isNotBlank() &&
            when (provider) {
                ProviderEnum.IMAP -> {
                    host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                }

                else -> {
                    if (authMethod == "OAUTH2") {
                        // For OAuth2 cloud: only name required; for on-premise: name and baseUrl required
                        name.isNotBlank() && (isCloud || baseUrl.isNotBlank())
                    } else {
                        // API TOKEN
                        if (provider == ProviderEnum.ATLASSIAN) {
                            baseUrl.isNotBlank() && httpBasicUsername.isNotBlank() && httpBasicPassword.isNotBlank()
                        } else {
                            baseUrl.isNotBlank() && httpBearerToken.isNotBlank()
                        }
                    }
                }
            }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Obecné", "Autentizace")

    val providerOptions = ProviderEnum.entries
    val authMethodOptions = listOf("OAUTH2", "API TOKEN")
    val isOAuth2 = (provider == ProviderEnum.GITHUB || provider == ProviderEnum.GITLAB || provider == ProviderEnum.ATLASSIAN) && authMethod == "OAUTH2"

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
                        value = provider?.name ?: "",
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
                        providerOptions.forEach { selectionOption ->
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

                // Auth method dropdown (only for non-email providers, hidden for OAuth2)
                if (provider != ProviderEnum.IMAP && !isOAuth2) {
                    ExposedDropdownMenuBox(
                        expanded = expandedGitAuthMethod,
                        onExpandedChange = { expandedGitAuthMethod = !expandedGitAuthMethod },
                    ) {
                        OutlinedTextField(
                            value = authMethod,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Metoda autentizace") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGitAuthMethod) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedGitAuthMethod,
                            onDismissRequest = { expandedGitAuthMethod = false },
                        ) {
                            authMethodOptions.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption) },
                                    onClick = {
                                        authMethod = selectionOption
                                        expandedGitAuthMethod = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cloud checkbox for OAuth2
                if (provider != ProviderEnum.IMAP && authMethod == "OAUTH2") {
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

                // Base URL (for GITHUB, GITLAB, ATLASSIAN) - hidden for cloud OAuth2
                if (provider != ProviderEnum.IMAP && (authMethod != "OAUTH2" || !isCloud)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // OAuth2 info message (for OAuth2 connections)
                if (isOAuth2) {
                    Text(
                        "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem a zde se nedají upravovat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // API Token / Basic Auth fields (for non-email providers with API TOKEN)
                if (provider != ProviderEnum.IMAP && authMethod == "API TOKEN") {
                    if (provider == ProviderEnum.ATLASSIAN) {
                        // Atlassian uses Basic Auth (username + token)
                        OutlinedTextField(
                            value = httpBasicUsername,
                            onValueChange = { httpBasicUsername = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = httpBasicPassword,
                            onValueChange = { httpBasicPassword = it },
                            label = { Text("API Token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        // GitHub/GitLab use Bearer Token
                        OutlinedTextField(
                            value = httpBearerToken,
                            onValueChange = { httpBearerToken = it },
                            label = { Text("API Token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Email fields (for IMAP)
                if (provider == ProviderEnum.IMAP) {
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

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Uživatelské jméno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Heslo") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
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
        },
        confirmButton = {
            Button(
                onClick = {
                    provider?.let { prov ->
                        val request =
                            when (prov) {
                                ProviderEnum.IMAP -> {
                                    ConnectionUpdateRequestDto(
                                        name = name,
                                        provider = prov,
                                        host = host,
                                        port = port.toIntOrNull(),
                                        username = username,
                                        password = password,
                                        useSsl = useSsl,
                                        folderName = folderName,
                                    )
                                }

                                else -> {
                                    if (authMethod == "OAUTH2") {
                                        // OAuth2 connections: only name, baseUrl, state, isCloud are editable; credentials managed by server
                                        ConnectionUpdateRequestDto(
                                            name = name,
                                            isCloud = isCloud,
                                            baseUrl = if (isCloud) null else baseUrl,
                                            state = state,
                                            gitProvider =
                                                if (prov in listOf(ProviderEnum.GITHUB, ProviderEnum.GITLAB)) {
                                                    prov.name
                                                } else {
                                                    null
                                                },
                                        )
                                    } else {
                                        // API TOKEN
                                        if (prov == ProviderEnum.ATLASSIAN) {
                                            ConnectionUpdateRequestDto(
                                                name = name,
                                                provider = prov,
                                                baseUrl = baseUrl,
                                                authType = HttpAuthTypeEnum.BASIC,
                                                httpBasicUsername = httpBasicUsername,
                                                httpBasicPassword = httpBasicPassword,
                                                state = state,
                                                gitProvider = null,
                                            )
                                        } else {
                                            ConnectionUpdateRequestDto(
                                                name = name,
                                                provider = prov,
                                                baseUrl = baseUrl,
                                                authType = HttpAuthTypeEnum.BEARER,
                                                httpBearerToken = httpBearerToken,
                                                state = state,
                                                gitProvider =
                                                    if (prov in
                                                        listOf(
                                                            ProviderEnum.GITHUB,
                                                            ProviderEnum.GITLAB,
                                                        )
                                                    ) {
                                                        prov.name
                                                    } else {
                                                        null
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        onSave(connection.id, request)
                    }
                },
                enabled = enabled && provider != null,
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
