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
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.jervis.ui.design.JPrimaryButton
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
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionUpdateRequestDto
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
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { loadData() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Načíst")
                }
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = { showCreateDialog = true }) {
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
                        repository.connections.createConnection(request)
                        snackbarHostState.showSnackbar("Připojení vytvořeno")
                        loadData()
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
                        loadData()
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
                                loadData()
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
                            label = { Text(connection.type, style = MaterialTheme.typography.labelSmall) },
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
    get() = baseUrl ?: gitRemoteUrl ?: host?.let { "$it${port?.let { port -> ":$port" } ?: ""}" } ?: "Bez adresy"

@Composable
private fun ConnectionCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("HTTP") }
    var baseUrl by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("NONE") }
    var httpBearerToken by remember { mutableStateOf("") }
    var httpBasicUsername by remember { mutableStateOf("") }
    var httpBasicPassword by remember { mutableStateOf("") }

    // OAuth2 fields
    var authorizationUrl by remember { mutableStateOf("") }
    var tokenUrl by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("") }
    var timeoutMs by remember { mutableStateOf("30000") }

    // Email fields
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("INBOX") }
    var useSsl by remember { mutableStateOf(true) }
    var useTls by remember { mutableStateOf(true) }

    // Git fields
    var gitRemoteUrl by remember { mutableStateOf("") }
    var gitProvider by remember { mutableStateOf("GITHUB") }

    // Rate limit
    var maxRequestsPerSecond by remember { mutableStateOf("10") }
    var maxRequestsPerMinute by remember { mutableStateOf("100") }

    // Atlassian
    var jiraProjectKey by remember { mutableStateOf("") }
    var confluenceSpaceKey by remember { mutableStateOf("") }
    var confluenceRootPageId by remember { mutableStateOf("") }
    var bitbucketRepoSlug by remember { mutableStateOf("") }

    var expandedType by remember { mutableStateOf(false) }
    var expandedAuth by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Obecné", "Autentizace", "Integrace", "Limity")

    val connectionTypes = listOf("HTTP", "OAUTH2", "IMAP", "POP3", "SMTP", "GIT")
    val authTypes = listOf("NONE", "BEARER", "BASIC")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vytvořit nové připojení") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (selectedTab) {
                        0 -> {
                            // Obecné
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Název připojení") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // Type dropdown
                            ExposedDropdownMenuBox(
                                expanded = expandedType,
                                onExpandedChange = { expandedType = it },
                            ) {
                                OutlinedTextField(
                                    value = type,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Typ připojení") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = expandedType,
                                    onDismissRequest = { expandedType = false },
                                ) {
                                    connectionTypes.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option) },
                                            onClick = {
                                                type = option
                                                expandedType = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (type == "HTTP" || type == "OAUTH2") {
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    label = { Text("Base URL") },
                                    placeholder = { Text("https://api.github.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = timeoutMs,
                                    onValueChange = { timeoutMs = it },
                                    label = { Text("Timeout (ms)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (type == "GIT") {
                                OutlinedTextField(
                                    value = gitRemoteUrl,
                                    onValueChange = { gitRemoteUrl = it },
                                    label = { Text("Git Remote URL") },
                                    placeholder = { Text("https://github.com/user/repo.git") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = gitProvider,
                                    onValueChange = { gitProvider = it },
                                    label = { Text("Git Provider (GITHUB, GITLAB, BITBUCKET, CUSTOM)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (type == "IMAP" || type == "POP3" || type == "SMTP") {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = { Text("Server (host)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it },
                                    label = { Text("Port") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (type == "IMAP") {
                                    OutlinedTextField(
                                        value = folderName,
                                        onValueChange = { folderName = it },
                                        label = { Text("Složka (např. INBOX)") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        1 -> {
                            // Autentizace
                            if (type == "HTTP") {
                                // Auth type dropdown
                                ExposedDropdownMenuBox(
                                    expanded = expandedAuth,
                                    onExpandedChange = { expandedAuth = it },
                                ) {
                                    OutlinedTextField(
                                        value = authType,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Typ autentizace") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuth) },
                                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expandedAuth,
                                        onDismissRequest = { expandedAuth = false },
                                    ) {
                                        authTypes.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                    authType = option
                                                    expandedAuth = false
                                                },
                                            )
                                        }
                                    }
                                }

                                if (authType == "BEARER") {
                                    OutlinedTextField(
                                        value = httpBearerToken,
                                        onValueChange = { httpBearerToken = it },
                                        label = { Text("Bearer Token") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                if (authType == "BASIC") {
                                    OutlinedTextField(
                                        value = httpBasicUsername,
                                        onValueChange = { httpBasicUsername = it },
                                        label = { Text("Uživatelské jméno") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = httpBasicPassword,
                                        onValueChange = { httpBasicPassword = it },
                                        label = { Text("Heslo") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            if (type == "OAUTH2") {
                                OutlinedTextField(
                                    value = authorizationUrl,
                                    onValueChange = { authorizationUrl = it },
                                    label = { Text("Authorization URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = tokenUrl,
                                    onValueChange = { tokenUrl = it },
                                    label = { Text("Token URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = clientSecret,
                                    onValueChange = { clientSecret = it },
                                    label = { Text("Client Secret") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = redirectUri,
                                    onValueChange = { redirectUri = it },
                                    label = { Text("Redirect URI") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = scope,
                                    onValueChange = { scope = it },
                                    label = { Text("Scope (volitelné)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (type == "IMAP" || type == "POP3" || type == "SMTP") {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Uživatelské jméno") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Heslo") },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                if (type == "IMAP" || type == "POP3") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = useSsl, onCheckedChange = { useSsl = it })
                                        Spacer(Modifier.width(4.dp))
                                        Text("Použít SSL")
                                    }
                                }

                                if (type == "SMTP") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = useTls, onCheckedChange = { useTls = it })
                                        Spacer(Modifier.width(4.dp))
                                        Text("Použít TLS")
                                    }
                                }
                            }
                        }

                        2 -> {
                            // Integrace
                            if (type == "HTTP" || type == "OAUTH2") {
                                OutlinedTextField(
                                    value = jiraProjectKey,
                                    onValueChange = { jiraProjectKey = it },
                                    label = { Text("Jira Project Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = confluenceSpaceKey,
                                    onValueChange = { confluenceSpaceKey = it },
                                    label = { Text("Confluence Space Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = confluenceRootPageId,
                                    onValueChange = { confluenceRootPageId = it },
                                    label = { Text("Confluence Root Page ID") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = bitbucketRepoSlug,
                                    onValueChange = { bitbucketRepoSlug = it },
                                    label = { Text("Bitbucket Repo Slug") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Integrace nejsou pro tento typ připojení k dispozici.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        3 -> {
                            // Limity
                            OutlinedTextField(
                                value = maxRequestsPerSecond,
                                onValueChange = { maxRequestsPerSecond = it },
                                label = { Text("Max požadavků za sekundu") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = maxRequestsPerMinute,
                                onValueChange = { maxRequestsPerMinute = it },
                                label = { Text("Max požadavků za minutu") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request =
                        ConnectionCreateRequestDto(
                            type = type,
                            name = name,
                            state = ConnectionStateEnum.NEW,
                            baseUrl = baseUrl.takeIf { it.isNotBlank() },
                            authType = if (type == "HTTP") authType else null,
                            httpBearerToken = if (authType == "BEARER") httpBearerToken.takeIf { it.isNotBlank() } else null,
                            httpBasicUsername = if (authType == "BASIC") httpBasicUsername.takeIf { it.isNotBlank() } else null,
                            httpBasicPassword = if (authType == "BASIC") httpBasicPassword.takeIf { it.isNotBlank() } else null,
                            authorizationUrl = if (type == "OAUTH2") authorizationUrl.takeIf { it.isNotBlank() } else null,
                            tokenUrl = if (type == "OAUTH2") tokenUrl.takeIf { it.isNotBlank() } else null,
                            clientSecret = if (type == "OAUTH2") clientSecret.takeIf { it.isNotBlank() } else null,
                            redirectUri = if (type == "OAUTH2") redirectUri.takeIf { it.isNotBlank() } else null,
                            scope = if (type == "OAUTH2") scope.takeIf { it.isNotBlank() } else null,
                            // Email fields
                            host = host.takeIf { it.isNotBlank() },
                            port = port.toIntOrNull(),
                            username = username.takeIf { it.isNotBlank() },
                            password = password.takeIf { it.isNotBlank() },
                            useSsl = useSsl,
                            useTls = useTls,
                            timeoutMs = timeoutMs.toLongOrNull(),
                            folderName = folderName.takeIf { it.isNotBlank() },
                            rateLimitConfig = com.jervis.dto.connection.RateLimitConfigDto(
                                maxRequestsPerSecond = maxRequestsPerSecond.toIntOrNull() ?: 10,
                                maxRequestsPerMinute = maxRequestsPerMinute.toIntOrNull() ?: 100
                            ),
                            jiraProjectKey = jiraProjectKey.takeIf { it.isNotBlank() },
                            confluenceSpaceKey = confluenceSpaceKey.takeIf { it.isNotBlank() },
                            confluenceRootPageId = confluenceRootPageId.takeIf { it.isNotBlank() },
                            bitbucketRepoSlug = bitbucketRepoSlug.takeIf { it.isNotBlank() },
                            gitRemoteUrl = gitRemoteUrl.takeIf { it.isNotBlank() },
                            gitProvider = gitProvider.takeIf { it.isNotBlank() },
                        )
                    onCreate(request)
                },
                enabled =
                    name.isNotBlank() &&
                        when (type) {
                            "HTTP" -> baseUrl.isNotBlank()
                            "OAUTH2" -> baseUrl.isNotBlank() && authorizationUrl.isNotBlank() && tokenUrl.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()
                            "IMAP", "POP3", "SMTP" -> host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                            "GIT" -> gitRemoteUrl.isNotBlank()
                            else -> true
                        },
            ) {
                Text("Vytvořit")
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
    var baseUrl by remember { mutableStateOf(connection.baseUrl ?: "") }
    var authType by remember { mutableStateOf(connection.authType ?: "NONE") }
    var httpBearerToken by remember { mutableStateOf("") }
    var httpBasicUsername by remember { mutableStateOf(connection.httpBasicUsername ?: "") }
    var httpBasicPassword by remember { mutableStateOf("") }
    var timeoutMs by remember { mutableStateOf(connection.timeoutMs?.toString() ?: "30000") }

    // OAuth2 fields
    var authorizationUrl by remember { mutableStateOf(connection.authorizationUrl ?: "") }
    var tokenUrl by remember { mutableStateOf(connection.tokenUrl ?: "") }
    var clientSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf(connection.redirectUri ?: "") }
    var scope by remember { mutableStateOf(connection.scope ?: "") }

    // Email fields
    var host by remember { mutableStateOf(connection.host ?: "") }
    var port by remember { mutableStateOf(connection.port?.toString() ?: "") }
    var username by remember { mutableStateOf(connection.username ?: "") }
    var password by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf(connection.folderName ?: "INBOX") }
    var useSsl by remember { mutableStateOf(connection.useSsl ?: true) }
    var useTls by remember { mutableStateOf(connection.useTls ?: true) }

    // Git fields
    var gitRemoteUrl by remember { mutableStateOf(connection.gitRemoteUrl ?: "") }
    var gitProvider by remember { mutableStateOf(connection.gitProvider ?: "GITHUB") }

    // Rate limit
    var maxRequestsPerSecond by remember { mutableStateOf(connection.rateLimitConfig?.maxRequestsPerSecond?.toString() ?: "10") }
    var maxRequestsPerMinute by remember { mutableStateOf(connection.rateLimitConfig?.maxRequestsPerMinute?.toString() ?: "100") }

    // Atlassian
    var jiraProjectKey by remember { mutableStateOf(connection.jiraProjectKey ?: "") }
    var confluenceSpaceKey by remember { mutableStateOf(connection.confluenceSpaceKey ?: "") }
    var confluenceRootPageId by remember { mutableStateOf(connection.confluenceRootPageId ?: "") }
    var bitbucketRepoSlug by remember { mutableStateOf(connection.bitbucketRepoSlug ?: "") }

    var expandedAuth by remember { mutableStateOf(false) }
    var expandedState by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Obecné", "Autentizace", "Integrace", "Limity")

    val authTypes = listOf("NONE", "BEARER", "BASIC")
    val states = ConnectionStateEnum.entries

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit připojení") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (selectedTab) {
                        0 -> {
                            // Obecné
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Název připojení") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // State dropdown
                            ExposedDropdownMenuBox(
                                expanded = expandedState,
                                onExpandedChange = { expandedState = it },
                            ) {
                                OutlinedTextField(
                                    value = state.name,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Stav připojení") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedState) },
                                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = expandedState,
                                    onDismissRequest = { expandedState = false },
                                ) {
                                    states.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.name) },
                                            onClick = {
                                                state = option
                                                expandedState = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (connection.type == "HTTP" || connection.type == "OAUTH2") {
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    label = { Text("Base URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = timeoutMs,
                                    onValueChange = { timeoutMs = it },
                                    label = { Text("Timeout (ms)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (connection.type == "GIT") {
                                OutlinedTextField(
                                    value = gitRemoteUrl,
                                    onValueChange = { gitRemoteUrl = it },
                                    label = { Text("Git Remote URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = gitProvider,
                                    onValueChange = { gitProvider = it },
                                    label = { Text("Git Provider (GITHUB, GITLAB, BITBUCKET, CUSTOM)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (connection.type == "IMAP" || connection.type == "POP3" || connection.type == "SMTP") {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = { Text("Server (host)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it },
                                    label = { Text("Port") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (connection.type == "IMAP") {
                                    OutlinedTextField(
                                        value = folderName,
                                        onValueChange = { folderName = it },
                                        label = { Text("Složka (např. INBOX)") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        1 -> {
                            // Autentizace
                            if (connection.type == "HTTP") {
                                // Auth type dropdown
                                ExposedDropdownMenuBox(
                                    expanded = expandedAuth,
                                    onExpandedChange = { expandedAuth = it },
                                ) {
                                    OutlinedTextField(
                                        value = authType,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Typ autentizace") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuth) },
                                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expandedAuth,
                                        onDismissRequest = { expandedAuth = false },
                                    ) {
                                        authTypes.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                    authType = option
                                                    expandedAuth = false
                                                },
                                            )
                                        }
                                    }
                                }

                                if (authType == "BEARER") {
                                    OutlinedTextField(
                                        value = httpBearerToken,
                                        onValueChange = { httpBearerToken = it },
                                        label = { Text("Bearer Token (nechte prázdné pro zachování)") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                if (authType == "BASIC") {
                                    OutlinedTextField(
                                        value = httpBasicUsername,
                                        onValueChange = { httpBasicUsername = it },
                                        label = { Text("Uživatelské jméno") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = httpBasicPassword,
                                        onValueChange = { httpBasicPassword = it },
                                        label = { Text("Heslo (nechte prázdné pro zachování)") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            if (connection.type == "OAUTH2") {
                                OutlinedTextField(
                                    value = authorizationUrl,
                                    onValueChange = { authorizationUrl = it },
                                    label = { Text("Authorization URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = tokenUrl,
                                    onValueChange = { tokenUrl = it },
                                    label = { Text("Token URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = clientSecret,
                                    onValueChange = { clientSecret = it },
                                    label = { Text("Client Secret (nechte prázdné pro zachování)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = redirectUri,
                                    onValueChange = { redirectUri = it },
                                    label = { Text("Redirect URI") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = scope,
                                    onValueChange = { scope = it },
                                    label = { Text("Scope (volitelné)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (connection.type == "IMAP" || connection.type == "POP3" || connection.type == "SMTP") {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Uživatelské jméno") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Heslo (nechte prázdné pro zachování)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                if (connection.type == "IMAP" || connection.type == "POP3") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = useSsl, onCheckedChange = { useSsl = it })
                                        Spacer(Modifier.width(4.dp))
                                        Text("Použít SSL")
                                    }
                                }

                                if (connection.type == "SMTP") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = useTls, onCheckedChange = { useTls = it })
                                        Spacer(Modifier.width(4.dp))
                                        Text("Použít TLS")
                                    }
                                }
                            }
                        }

                        2 -> {
                            // Integrace
                            if (connection.type == "HTTP" || connection.type == "OAUTH2") {
                                OutlinedTextField(
                                    value = jiraProjectKey,
                                    onValueChange = { jiraProjectKey = it },
                                    label = { Text("Jira Project Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = confluenceSpaceKey,
                                    onValueChange = { confluenceSpaceKey = it },
                                    label = { Text("Confluence Space Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = confluenceRootPageId,
                                    onValueChange = { confluenceRootPageId = it },
                                    label = { Text("Confluence Root Page ID") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = bitbucketRepoSlug,
                                    onValueChange = { bitbucketRepoSlug = it },
                                    label = { Text("Bitbucket Repo Slug") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Integrace nejsou pro tento typ připojení k dispozici.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        3 -> {
                            // Limity
                            OutlinedTextField(
                                value = maxRequestsPerSecond,
                                onValueChange = { maxRequestsPerSecond = it },
                                label = { Text("Max požadavků za sekundu") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = maxRequestsPerMinute,
                                onValueChange = { maxRequestsPerMinute = it },
                                label = { Text("Max požadavků za minutu") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request =
                        ConnectionUpdateRequestDto(
                            name = name.takeIf { it.isNotBlank() },
                            state = state,
                            baseUrl = baseUrl.takeIf { it.isNotBlank() },
                            authType = if (connection.type == "HTTP") authType else null,
                            httpBearerToken = if (authType == "BEARER" && httpBearerToken.isNotBlank()) httpBearerToken else null,
                            httpBasicUsername = if (authType == "BASIC" && httpBasicUsername.isNotBlank()) httpBasicUsername else null,
                            httpBasicPassword = if (authType == "BASIC" && httpBasicPassword.isNotBlank()) httpBasicPassword else null,
                            authorizationUrl = if (connection.type == "OAUTH2") authorizationUrl.takeIf { it.isNotBlank() } else null,
                            tokenUrl = if (connection.type == "OAUTH2") tokenUrl.takeIf { it.isNotBlank() } else null,
                            clientSecret = if (connection.type == "OAUTH2" && clientSecret.isNotBlank()) clientSecret else null,
                            redirectUri = if (connection.type == "OAUTH2") redirectUri.takeIf { it.isNotBlank() } else null,
                            scope = if (connection.type == "OAUTH2" && scope.isNotBlank()) scope else null,
                            // Email fields
                            host = host.takeIf { it.isNotBlank() },
                            port = port.toIntOrNull(),
                            username = username.takeIf { it.isNotBlank() },
                            password = password.takeIf { it.isNotBlank() },
                            useSsl = useSsl,
                            useTls = useTls,
                            timeoutMs = timeoutMs.toLongOrNull(),
                            folderName = folderName.takeIf { it.isNotBlank() },
                            rateLimitConfig = com.jervis.dto.connection.RateLimitConfigDto(
                                maxRequestsPerSecond = maxRequestsPerSecond.toIntOrNull() ?: 10,
                                maxRequestsPerMinute = maxRequestsPerMinute.toIntOrNull() ?: 100
                            ),
                            jiraProjectKey = jiraProjectKey.takeIf { it.isNotBlank() },
                            confluenceSpaceKey = confluenceSpaceKey.takeIf { it.isNotBlank() },
                            confluenceRootPageId = confluenceRootPageId.takeIf { it.isNotBlank() },
                            bitbucketRepoSlug = bitbucketRepoSlug.takeIf { it.isNotBlank() },
                            gitRemoteUrl = gitRemoteUrl.takeIf { it.isNotBlank() },
                            gitProvider = gitProvider.takeIf { it.isNotBlank() },
                        )
                    onSave(connection.id, request)
                },
                enabled = name.isNotBlank(),
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
