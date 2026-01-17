package com.jervis.ui

// (imports above already include connection DTOs)
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.domain.language.LanguageEnum
import com.jervis.dto.ClientDto
import com.jervis.dto.GitConfigDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.util.pickTextFileContent
import kotlinx.coroutines.launch

/**
 * Settings Screen - Configuration for runtime values
 *
 * Contains:
 * - Clients (basic info, connections)
 * - Projects (per client)
 *
 * Note: Standalone "Connections" screen/tab has been removed. Connection management lives inside
 * Client/Project edit per guidelines (exclusive ownership, duplicate when needed).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Clients", "Projects", "Connections")

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                        focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    } else {
                        false
                    }
                },
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tab content
        when (selectedTab) {
            0 -> ClientsTabContent(repository)
            1 -> ProjectsTabContent(repository)
            2 -> ConnectionsTabContent(repository)
        }
    }
}

@Composable
private fun ClientsTabContent(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    // no separate create connection dialog here
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    fun loadClients() {
        scope.launch {
            loading = true
            error = null
            try {
                clients = repository.clients.listClients()
                connections = repository.connections.listConnections()
                projects = repository.projects.getAllProjects()
            } catch (e: Exception) {
                error = "Failed to load clients: ${e.message}"
                repository.errorLogs.recordUiError(
                    message = error!!,
                    stackTrace = e.toString(),
                    causeType = e::class.simpleName,
                )
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadClients()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Clients", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) {
                    Text("Create New")
                }
                Button(onClick = { loadClients() }) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                CircularProgressIndicator()
            }

            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            clients.isEmpty() -> {
                Text("No clients configured")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(clients) { client ->
                        ClientCard(
                            client = client,
                            onClick = {
                                selectedClient = client
                                showEditDialog = true
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Inline overview of all connections (visibility in Settings window)
                ConnectionsOverviewPanel(
                    connections = connections,
                    clients = clients,
                    projects = projects,
                    onCreateConnection = { req ->
                        scope.launch {
                            try {
                                repository.connections.createConnection(req)
                                connections = repository.connections.listConnections()
                            } catch (e: Exception) {
                                error = "Failed to create connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                    onTestConnection = { id ->
                        scope.launch {
                            try {
                                val result = repository.connections.testConnection(id)
                                if (result.success) {
                                    repository.connections.updateConnection(
                                        id,
                                        com.jervis.dto.connection.ConnectionUpdateRequestDto(
                                            state = com.jervis.dto.connection.ConnectionStateEnum.VALID,
                                        ),
                                    )
                                    connections = repository.connections.listConnections()
                                }
                            } catch (e: Exception) {
                                error = "Failed to test connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                    onEditConnection = { id, updateReq, onResult ->
                        scope.launch {
                            try {
                                repository.connections.updateConnection(id, updateReq)
                                connections = repository.connections.listConnections()
                                onResult(true, null)
                            } catch (e: Exception) {
                                error = "Failed to update connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                                onResult(false, e.message)
                            }
                        }
                    },
                    onDuplicate = { conn ->
                        scope.launch {
                            try {
                                val req =
                                    com.jervis.dto.connection.ConnectionCreateRequestDto(
                                        type = conn.type,
                                        name = conn.name + " (copy)",
                                        state = com.jervis.dto.connection.ConnectionStateEnum.NEW,
                                        baseUrl = conn.baseUrl,
                                        authType = conn.authType,
                                        httpBasicUsername = conn.httpBasicUsername,
                                        httpBasicPassword = conn.httpBasicPassword,
                                        httpBearerToken = conn.httpBearerToken,
                                        timeoutMs = conn.timeoutMs,
                                        host = conn.host,
                                        port = conn.port,
                                        username = conn.username,
                                        password = conn.password,
                                        useSsl = conn.useSsl,
                                        useTls = conn.useTls,
                                        authorizationUrl = conn.authorizationUrl,
                                        tokenUrl = conn.tokenUrl,
                                        clientSecret = conn.clientSecret,
                                        redirectUri = conn.redirectUri,
                                        scope = conn.scope,
                                    )
                                repository.connections.createConnection(req)
                                connections = repository.connections.listConnections()
                            } catch (e: Exception) {
                                error = "Failed to duplicate connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    if (showCreateDialog) {
        ClientCreateDialog(
            connections = connections,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, connectionId, onResult ->
                scope.launch {
                    try {
                        val newClient =
                            ClientDto(
                                name = name,
                                connectionIds = listOfNotNull(connectionId),
                            )
                        repository.clients.createClient(newClient)
                        showCreateDialog = false
                        loadClients()
                        onResult(true, null)
                    } catch (e: Exception) {
                        error = "Failed to create client: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false, e.message)
                    }
                }
            },
        )
    }

    if (showEditDialog && selectedClient != null) {
        ClientEditDialog(
            client = selectedClient!!,
            connections = connections,
            clients = clients,
            projects = projects,
            onCreateConnection = { req, onCreated ->
                scope.launch {
                    try {
                        val created = repository.connections.createConnection(req)
                        onCreated(created.id)
                        connections = repository.connections.listConnections()
                    } catch (e: Exception) {
                        error = "Failed to create connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
            onTestConnection = { id, onResult ->
                scope.launch {
                    try {
                        val result = repository.connections.testConnection(id)
                        if (result.success) {
                            repository.connections.updateConnection(
                                id,
                                com.jervis.dto.connection.ConnectionUpdateRequestDto(
                                    state = com.jervis.dto.connection.ConnectionStateEnum.VALID,
                                ),
                            )
                            connections = repository.connections.listConnections()
                        }
                        onResult(result.success)
                    } catch (e: Exception) {
                        error = "Failed to test connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false)
                    }
                }
            },
            onUpdateConnection = { id, req, onResult ->
                scope.launch {
                    try {
                        repository.connections.updateConnection(id, req)
                        connections = repository.connections.listConnections()
                        onResult(true, null)
                    } catch (e: Exception) {
                        error = "Failed to update connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false, e.message)
                    }
                }
            },
            onDuplicate = { conn, onDuplicated ->
                scope.launch {
                    try {
                        val req =
                            ConnectionCreateRequestDto(
                                type = conn.type,
                                name = conn.name + " (copy)",
                                state = ConnectionStateEnum.NEW,
                                baseUrl = conn.baseUrl,
                                authType = conn.authType,
                                httpBasicUsername = conn.httpBasicUsername,
                                httpBasicPassword = conn.httpBasicPassword,
                                httpBearerToken = conn.httpBearerToken,
                                timeoutMs = conn.timeoutMs,
                                host = conn.host,
                                port = conn.port,
                                username = conn.username,
                                password = conn.password,
                                useSsl = conn.useSsl,
                                useTls = conn.useTls,
                                authorizationUrl = conn.authorizationUrl,
                                tokenUrl = conn.tokenUrl,
                                clientSecret = conn.clientSecret,
                                redirectUri = conn.redirectUri,
                                scope = conn.scope,
                            )
                        val created = repository.connections.createConnection(req)
                        onDuplicated(created.id)
                        // Refresh available connections so the dialog can show the new one if needed
                        connections = repository.connections.listConnections()
                    } catch (e: Exception) {
                        error = "Failed to duplicate connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
            onDismiss = {
                showEditDialog = false
                selectedClient = null
            },
            onUpdate = { updatedClient: ClientDto ->
                scope.launch {
                    try {
                        repository.clients.updateClient(updatedClient.id, updatedClient)
                        showEditDialog = false
                        selectedClient = null
                        loadClients()
                    } catch (e: Exception) {
                        error = "Failed to update client: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ClientCard(
    client: ClientDto,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(client.name, style = MaterialTheme.typography.titleMedium)

            if (client.connectionIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connections: ${client.connectionIds.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (client.gitProvider != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Git: ${client.gitProvider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectsTabContent(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    fun loadProjects() {
        scope.launch {
            loading = true
            error = null
            try {
                projects = repository.projects.getAllProjects()
                clients = repository.clients.listClients()
                connections = repository.connections.listConnections()
            } catch (e: Exception) {
                error = "Failed to load projects: ${e.message}"
                repository.errorLogs.recordUiError(
                    message = error!!,
                    stackTrace = e.toString(),
                    causeType = e::class.simpleName,
                )
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadProjects()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Projects", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) {
                    Text("Create New")
                }
                Button(onClick = { loadProjects() }) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                CircularProgressIndicator()
            }

            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            projects.isEmpty() -> {
                Text("No projects configured")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(projects) { project ->
                        ProjectCard(
                            project = project,
                            clients = clients,
                            onClick = {
                                selectedProject = project
                                showEditDialog = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ProjectCreateDialog(
            clients = clients,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, clientId, onResult ->
                scope.launch {
                    try {
                        val newProject =
                            ProjectDto(
                                name = name,
                                clientId = clientId,
                            )
                        repository.projects.saveProject(newProject)
                        showCreateDialog = false
                        loadProjects()
                        onResult(true, null)
                    } catch (e: Exception) {
                        error = "Failed to create project: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false, e.message)
                    }
                }
            },
        )
    }

    if (showEditDialog && selectedProject != null) {
        ProjectEditDialog(
            project = selectedProject!!,
            clients = clients,
            connections = connections,
            allProjects = projects,
            onCreateConnection = { req, onCreated ->
                scope.launch {
                    try {
                        val created = repository.connections.createConnection(req)
                        onCreated(created.id)
                        connections = repository.connections.listConnections()
                    } catch (e: Exception) {
                        error = "Failed to create connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
            onTestConnection = { id, onResult ->
                scope.launch {
                    try {
                        val result = repository.connections.testConnection(id)
                        if (result.success) {
                            repository.connections.updateConnection(
                                id,
                                com.jervis.dto.connection.ConnectionUpdateRequestDto(
                                    state = com.jervis.dto.connection.ConnectionStateEnum.VALID,
                                ),
                            )
                            connections = repository.connections.listConnections()
                        }
                        onResult(result.success)
                    } catch (e: Exception) {
                        error = "Failed to test connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false)
                    }
                }
            },
            onUpdateConnection = { id, req, onResult ->
                scope.launch {
                    try {
                        repository.connections.updateConnection(id, req)
                        connections = repository.connections.listConnections()
                        onResult(true, null)
                    } catch (e: Exception) {
                        error = "Failed to update connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                        onResult(false, e.message)
                    }
                }
            },
            onDuplicate = { conn, onDuplicated ->
                scope.launch {
                    try {
                        val req =
                            ConnectionCreateRequestDto(
                                type = conn.type,
                                name = conn.name + " (copy)",
                                state = ConnectionStateEnum.NEW,
                                baseUrl = conn.baseUrl,
                                authType = conn.authType,
                                httpBasicUsername = conn.httpBasicUsername,
                                httpBasicPassword = conn.httpBasicPassword,
                                httpBearerToken = conn.httpBearerToken,
                                timeoutMs = conn.timeoutMs,
                                host = conn.host,
                                port = conn.port,
                                username = conn.username,
                                password = conn.password,
                                useSsl = conn.useSsl,
                                useTls = conn.useTls,
                                authorizationUrl = conn.authorizationUrl,
                                tokenUrl = conn.tokenUrl,
                                clientSecret = conn.clientSecret,
                                redirectUri = conn.redirectUri,
                                scope = conn.scope,
                            )
                        val created = repository.connections.createConnection(req)
                        onDuplicated(created.id)
                        connections = repository.connections.listConnections()
                    } catch (e: Exception) {
                        error = "Failed to duplicate connection: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
            onDismiss = {
                showEditDialog = false
                selectedProject = null
            },
            onUpdate = { updatedProject: ProjectDto ->
                scope.launch {
                    try {
                        repository.projects.updateProject(updatedProject)
                        showEditDialog = false
                        selectedProject = null
                        loadProjects()
                    } catch (e: Exception) {
                        error = "Failed to update project: ${e.message}"
                        repository.errorLogs.recordUiError(
                            message = error!!,
                            stackTrace = e.toString(),
                            causeType = e::class.simpleName,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectDto,
    clients: List<ClientDto>,
    onClick: () -> Unit = {},
) {
    val clientName = clients.firstOrNull { it.id == project.clientId }?.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(project.name ?: "Unnamed", style = MaterialTheme.typography.titleMedium)

            if (clientName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Client: $clientName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (project.connectionIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connections: ${project.connectionIds.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Legacy ConnectionsTabContent removed – connections are managed inside Client/Project edit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientCreateDialog(
    connections: List<ConnectionResponseDto>,
    onDismiss: () -> Unit,
    onCreate: (name: String, connectionId: String?, onResult: (Boolean, String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create Client") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Client Name") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                )

                Text("Connection (optional):", style = MaterialTheme.typography.labelMedium)

                if (connections.isEmpty()) {
                    Text(
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (!isSaving) expanded = it },
                    ) {
                        OutlinedTextField(
                            value = connections.firstOrNull { it.id == selectedConnectionId }?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Connection") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            enabled = !isSaving,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    selectedConnectionId = null
                                    expanded = false
                                },
                            )
                            connections.forEach { conn ->
                                DropdownMenuItem(
                                    text = { Text("${conn.name} (${conn.type})") },
                                    onClick = {
                                        selectedConnectionId = conn.id
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Saving…")
                    }
                }
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSaving = true
                    errorText = null
                    onCreate(name, selectedConnectionId) { ok, err ->
                        if (!ok) {
                            isSaving = false
                            errorText = err ?: "Failed to save"
                        }
                    }
                },
                enabled = name.isNotBlank() && !isSaving,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCreateDialog(
    clients: List<ClientDto>,
    onDismiss: () -> Unit,
    onCreate: (name: String, clientId: String, onResult: (Boolean, String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create Project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                )

                Text("Client:", style = MaterialTheme.typography.labelMedium)

                if (clients.isEmpty()) {
                    Text(
                        "No clients available. Create a client first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (!isSaving) expanded = it },
                    ) {
                        OutlinedTextField(
                            value = clients.firstOrNull { it.id == selectedClientId }?.name ?: "Select client",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Client") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            enabled = !isSaving,
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
                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Saving…")
                    }
                }
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedClientId?.let {
                        isSaving = true
                        errorText = null
                        onCreate(name, it) { ok, err ->
                            if (!ok) {
                                isSaving = false
                                errorText = err ?: "Failed to save"
                            }
                        }
                    }
                },
                enabled = name.isNotBlank() && selectedClientId != null && !isSaving,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        },
    )
}

// Legacy connection dialogs removed – connections are now managed inside Client/Project edit

@Composable
private fun ClientEditDialog(
    client: ClientDto,
    connections: List<ConnectionResponseDto>,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onCreateConnection: ((ConnectionCreateRequestDto, (String) -> Unit) -> Unit)? = null,
    onTestConnection: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onUpdateConnection: ((String, com.jervis.dto.connection.ConnectionUpdateRequestDto, (Boolean, String?) -> Unit) -> Unit)? = null,
    onDuplicate: (conn: ConnectionResponseDto, onDuplicated: (newId: String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (ClientDto) -> Unit,
) {
    var name by remember { mutableStateOf(client.name) }
    // Use immutable Set and always assign a new instance to trigger recomposition
    var selectedConnectionIds by remember { mutableStateOf(client.connectionIds.toSet()) }
    var gitProvider by remember { mutableStateOf<GitProviderEnum?>(client.gitProvider) }
    var gitAuthType by remember { mutableStateOf<GitAuthTypeEnum?>(client.gitAuthType) }
    var gitUserName by remember { mutableStateOf(client.gitConfig?.gitUserName ?: "") }
    var gitUserEmail by remember { mutableStateOf(client.gitConfig?.gitUserEmail ?: "") }
    var commitMessageTemplate by remember { mutableStateOf(client.gitConfig?.commitMessageTemplate ?: "") }
    var requireGpgSign by remember { mutableStateOf(client.gitConfig?.requireGpgSign ?: false) }
    var gpgKeyId by remember { mutableStateOf(client.gitConfig?.gpgKeyId ?: "") }
    var requireLinearHistory by remember { mutableStateOf(client.gitConfig?.requireLinearHistory ?: false) }
    var conventionalCommits by remember { mutableStateOf(client.gitConfig?.conventionalCommits ?: false) }
    var commitRulesText by remember {
        mutableStateOf(
            client.gitConfig
                ?.commitRules
                ?.entries
                ?.joinToString("\n") { "${it.key}=${it.value}" } ?: "",
        )
    }

    var sshPrivateKey by remember { mutableStateOf(client.gitCredentials?.sshPrivateKey ?: "") }
    var sshPublicKey by remember { mutableStateOf(client.gitCredentials?.sshPublicKey ?: "") }
    var sshPassphrase by remember { mutableStateOf(client.gitCredentials?.sshPassphrase ?: "") }
    var httpsToken by remember { mutableStateOf(client.gitCredentials?.httpsToken ?: "") }
    var httpsUsername by remember { mutableStateOf(client.gitCredentials?.httpsUsername ?: "") }
    var httpsPassword by remember { mutableStateOf(client.gitCredentials?.httpsPassword ?: "") }
    var gpgPrivateKey by remember { mutableStateOf(client.gitCredentials?.gpgPrivateKey ?: "") }
    var gpgPublicKey by remember { mutableStateOf(client.gitCredentials?.gpgPublicKey ?: "") }
    var gpgPassphrase by remember { mutableStateOf(client.gitCredentials?.gpgPassphrase ?: "") }

    var defaultLanguage by remember { mutableStateOf(client.defaultLanguageEnum) }
    var lastSelectedProjectId by remember { mutableStateOf(client.lastSelectedProjectId) }

    var providerExpanded by remember { mutableStateOf(false) }
    var authExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var projectExpanded by remember { mutableStateOf(false) }

    var showCreateConnectionDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Client") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Client Name") },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Git provider & auth type
                Text("Git Settings:", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = gitProvider?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Git Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    gitProvider = null
                                    providerExpanded = false
                                },
                            )
                            GitProviderEnum.entries.forEach { prov ->
                                DropdownMenuItem(
                                    text = { Text(prov.name) },
                                    onClick = {
                                        gitProvider = prov
                                        providerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = authExpanded,
                        onExpandedChange = { authExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = gitAuthType?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Git Auth Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = {
                                    gitAuthType = null
                                    authExpanded = false
                                },
                            )
                            GitAuthTypeEnum.entries.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a.name) },
                                    onClick = {
                                        gitAuthType = a
                                        authExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = gitUserName,
                    onValueChange = { gitUserName = it },
                    label = { Text("Git User Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gitUserEmail,
                    onValueChange = { gitUserEmail = it },
                    label = { Text("Git User Email") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commitMessageTemplate,
                    onValueChange = { commitMessageTemplate = it },
                    label = { Text("Commit Message Template") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requireGpgSign, onCheckedChange = { requireGpgSign = it })
                    Text("Require GPG Sign")
                }
                OutlinedTextField(
                    value = gpgKeyId,
                    onValueChange = { gpgKeyId = it },
                    label = { Text("GPG Key ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requireLinearHistory, onCheckedChange = { requireLinearHistory = it })
                    Text("Require Linear History")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = conventionalCommits, onCheckedChange = { conventionalCommits = it })
                    Text("Conventional Commits")
                }
                OutlinedTextField(
                    value = commitRulesText,
                    onValueChange = { commitRulesText = it },
                    label = { Text("Commit Rules (key=value per line)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Git Credentials (plaintext):", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sshPrivateKey,
                        onValueChange = { sshPrivateKey = it },
                        label = { Text("SSH Private Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            pickTextFileContent("Load SSH Private Key")?.let {
                                sshPrivateKey = it
                            }
                        }) { Text("Load from file") }
                    }

                    OutlinedTextField(
                        value = sshPublicKey,
                        onValueChange = { sshPublicKey = it },
                        label = { Text("SSH Public Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            pickTextFileContent("Load SSH Public Key")?.let {
                                sshPublicKey = it
                            }
                        }) { Text("Load from file") }
                    }

                    OutlinedTextField(
                        value = sshPassphrase,
                        onValueChange = { sshPassphrase = it },
                        label = { Text("SSH Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Divider()

                    OutlinedTextField(
                        value = httpsToken,
                        onValueChange = { httpsToken = it },
                        label = { Text("HTTPS Token") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = httpsUsername,
                        onValueChange = { httpsUsername = it },
                        label = { Text("HTTPS Username") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = httpsPassword,
                        onValueChange = { httpsPassword = it },
                        label = { Text("HTTPS Password") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Divider()

                    OutlinedTextField(
                        value = gpgPrivateKey,
                        onValueChange = { gpgPrivateKey = it },
                        label = { Text("GPG Private Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            pickTextFileContent("Load GPG Private Key")?.let {
                                gpgPrivateKey = it
                            }
                        }) { Text("Load from file") }
                    }
                    OutlinedTextField(
                        value = gpgPublicKey,
                        onValueChange = { gpgPublicKey = it },
                        label = { Text("GPG Public Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            pickTextFileContent("Load GPG Public Key")?.let {
                                gpgPublicKey = it
                            }
                        }) { Text("Load from file") }
                    }
                    OutlinedTextField(
                        value = gpgPassphrase,
                        onValueChange = { gpgPassphrase = it },
                        label = { Text("GPG Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Language and last selected project
                ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
                    OutlinedTextField(
                        value = defaultLanguage.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        LanguageEnum.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.name) },
                                onClick = {
                                    defaultLanguage = lang
                                    langExpanded = false
                                },
                            )
                        }
                    }
                }

                val clientProjects = projects.filter { it.clientId == client.id }
                ExposedDropdownMenuBox(expanded = projectExpanded, onExpandedChange = { projectExpanded = it }) {
                    OutlinedTextField(
                        value = clientProjects.firstOrNull { it.id == lastSelectedProjectId }?.name ?: "None",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Last Selected Project") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = projectExpanded, onDismissRequest = { projectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                lastSelectedProjectId = null
                                projectExpanded = false
                            },
                        )
                        clientProjects.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name ?: "Unnamed") },
                                onClick = {
                                    lastSelectedProjectId = p.id
                                    projectExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Connections:", style = MaterialTheme.typography.labelMedium)
                    if (onCreateConnection != null) {
                        TextButton(onClick = { showCreateConnectionDialog = true }) { Text("Create Connection") }
                    }
                }

                if (connections.isEmpty()) {
                    Text(
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(connections) { conn ->
                            val ownedByThisClient = client.connectionIds.contains(conn.id)
                            val ownerLabel: String? =
                                when {
                                    ownedByThisClient -> {
                                        "Attached to this client"
                                    }

                                    clients.any { it.connectionIds.contains(conn.id) } -> {
                                        val c = clients.first { it.connectionIds.contains(conn.id) }
                                        "Client: ${c.name}"
                                    }

                                    projects.any { it.connectionIds.contains(conn.id) } -> {
                                        val p = projects.first { it.connectionIds.contains(conn.id) }
                                        "Project: ${p.name}"
                                    }

                                    else -> {
                                        null
                                    }
                                }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${conn.name} (${conn.type})",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = "State: ${conn.state}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            val subtitle =
                                                buildString {
                                                    when (conn.type.uppercase()) {
                                                        "HTTP" -> {
                                                            append(conn.baseUrl ?: "")
                                                        }

                                                        "IMAP", "POP3", "SMTP" -> {
                                                            append(
                                                                listOfNotNull(
                                                                    conn.host,
                                                                    conn.username,
                                                                ).joinToString(" · "),
                                                            )
                                                        }

                                                        "OAUTH2" -> {
                                                            append(
                                                                listOfNotNull(
                                                                    conn.authorizationUrl,
                                                                ).joinToString(" · "),
                                                            )
                                                        }
                                                    }
                                                }
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                ownerLabel ?: "Unattached",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        if (ownerLabel == null || ownedByThisClient) {
                                            val checked = selectedConnectionIds.contains(conn.id)
                                            Row(
                                                modifier =
                                                    Modifier.clickable {
                                                        selectedConnectionIds =
                                                            if (checked) {
                                                                selectedConnectionIds - conn.id
                                                            } else {
                                                                selectedConnectionIds +
                                                                    conn.id
                                                            }
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { isChecked ->
                                                        selectedConnectionIds =
                                                            if (isChecked) {
                                                                selectedConnectionIds + conn.id
                                                            } else {
                                                                selectedConnectionIds -
                                                                    conn.id
                                                            }
                                                    },
                                                )
                                                Text(if (checked) "Attached" else "Attach")
                                                if (onTestConnection != null) {
                                                    TextButton(onClick = {
                                                        onTestConnection.invoke(conn.id) { ok ->
                                                            // no local state handling required; repository refresh happens at caller
                                                        }
                                                    }) { Text("Test") }
                                                }
                                                if (onUpdateConnection != null) {
                                                    var showEdit by remember { mutableStateOf(false) }
                                                    TextButton(onClick = { showEdit = true }) { Text("Edit") }
                                                    if (showEdit) {
                                                        ConnectionEditDialog(
                                                            connection = conn,
                                                            onDismiss = { showEdit = false },
                                                            onSave = { req, onResult ->
                                                                onUpdateConnection?.invoke(conn.id, req) { ok, err ->
                                                                    if (ok) showEdit = false
                                                                    onResult(ok, err)
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        } else if (!ownedByThisClient) {
                                            Button(onClick = {
                                                onDuplicate(conn) { newId ->
                                                    selectedConnectionIds = selectedConnectionIds + newId
                                                }
                                            }) {
                                                Text("Duplicate")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Hint: A connection can belong to only one Client or Project. If it’s owned elsewhere, use Duplicate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showCreateConnectionDialog && onCreateConnection != null) {
                    ConnectionQuickCreateDialog(
                        onDismiss = { showCreateConnectionDialog = false },
                        onCreate = { req ->
                            onCreateConnection.invoke(req) { newId ->
                                selectedConnectionIds = selectedConnectionIds + newId
                                showCreateConnectionDialog = false
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Parse commit rules
                    val commitRules: Map<String, String> =
                        commitRulesText
                            .lines()
                            .mapNotNull { line ->
                                val idx = line.indexOf('=')
                                if (idx <= 0) {
                                    null
                                } else {
                                    (
                                        line.substring(0, idx).trim() to
                                            line
                                                .substring(idx + 1)
                                                .trim()
                                    )
                                }
                            }.toMap()

                    val updated =
                        client.copy(
                            name = name,
                            gitProvider = gitProvider,
                            gitAuthType = gitAuthType,
                            gitConfig =
                                GitConfigDto(
                                    gitUserName = gitUserName.ifBlank { null },
                                    gitUserEmail = gitUserEmail.ifBlank { null },
                                    commitMessageTemplate = commitMessageTemplate.ifBlank { null },
                                    requireGpgSign = requireGpgSign,
                                    gpgKeyId = gpgKeyId.ifBlank { null },
                                    requireLinearHistory = requireLinearHistory,
                                    conventionalCommits = conventionalCommits,
                                    commitRules = commitRules,
                                ),
                            gitCredentials =
                                GitCredentialsDto(
                                    sshPrivateKey = sshPrivateKey.ifBlank { null },
                                    sshPublicKey = sshPublicKey.ifBlank { null },
                                    sshPassphrase = sshPassphrase.ifBlank { null },
                                    httpsToken = httpsToken.ifBlank { null },
                                    httpsUsername = httpsUsername.ifBlank { null },
                                    httpsPassword = httpsPassword.ifBlank { null },
                                    gpgPrivateKey = gpgPrivateKey.ifBlank { null },
                                    gpgPublicKey = gpgPublicKey.ifBlank { null },
                                    gpgPassphrase = gpgPassphrase.ifBlank { null },
                                ),
                            defaultLanguageEnum = defaultLanguage,
                            lastSelectedProjectId = lastSelectedProjectId,
                            connectionIds = selectedConnectionIds.toList(),
                        )
                    onUpdate(updated)
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ConnectionsOverviewPanel(
    connections: List<ConnectionResponseDto>,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onCreateConnection: (ConnectionCreateRequestDto) -> Unit,
    onTestConnection: (String) -> Unit,
    onEditConnection: (String, com.jervis.dto.connection.ConnectionUpdateRequestDto, (Boolean, String?) -> Unit) -> Unit,
    onDuplicate: (ConnectionResponseDto) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("All Connections", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showCreate = true }) { Text("Create Connection") }
        }

        if (connections.isEmpty()) {
            Text(
                "No connections yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(connections) { conn ->
                    val ownerLabel: String? =
                        when {
                            clients.any { it.connectionIds.contains(conn.id) } -> {
                                val c = clients.first { it.connectionIds.contains(conn.id) }
                                "Client: ${c.name}"
                            }

                            projects.any { it.connectionIds.contains(conn.id) } -> {
                                val p = projects.first { it.connectionIds.contains(conn.id) }
                                "Project: ${p.name}"
                            }

                            else -> {
                                null
                            }
                        }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${conn.name} (${conn.type})", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "State: ${conn.state}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val subtitle =
                                        buildString {
                                            when (conn.type.uppercase()) {
                                                "HTTP" -> {
                                                    append(conn.baseUrl ?: "")
                                                }

                                                "IMAP", "POP3", "SMTP" -> {
                                                    append(
                                                        listOfNotNull(
                                                            conn.host,
                                                            conn.username,
                                                        ).joinToString(" · "),
                                                    )
                                                }

                                                "OAUTH2" -> {
                                                    append(
                                                        listOfNotNull(
                                                            conn.authorizationUrl,
                                                        ).joinToString(" · "),
                                                    )
                                                }
                                            }
                                        }
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        ownerLabel ?: "Unattached",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    var showEdit by remember { mutableStateOf(false) }
                                    TextButton(onClick = { showEdit = true }) { Text("Edit") }
                                    if (showEdit) {
                                        ConnectionEditDialog(
                                            connection = conn,
                                            onDismiss = { showEdit = false },
                                            onSave = { updateReq, onResult ->
                                                onEditConnection(conn.id, updateReq) { ok, err ->
                                                    if (ok) showEdit = false
                                                    onResult(ok, err)
                                                }
                                            },
                                        )
                                    }
                                    TextButton(onClick = { onTestConnection(conn.id) }) { Text("Test") }
                                    OutlinedButton(onClick = { onDuplicate(conn) }) { Text("Duplicate") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreate) {
            ConnectionQuickCreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { req ->
                    onCreateConnection(req)
                    showCreate = false
                },
            )
        }
    }
}

@Composable
private fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    onDismiss: () -> Unit,
    onSave: (com.jervis.dto.connection.ConnectionUpdateRequestDto, (Boolean, String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(connection.name) }
    var baseUrl by remember { mutableStateOf(connection.baseUrl ?: "") }
    var authType by remember { mutableStateOf((connection.authType ?: "NONE").uppercase()) }
    var httpBasicUsername by remember { mutableStateOf(connection.httpBasicUsername ?: "") }
    var httpBasicPassword by remember { mutableStateOf(connection.httpBasicPassword ?: "") }
    var httpBearerToken by remember { mutableStateOf(connection.httpBearerToken ?: "") }
    var timeoutMs by remember { mutableStateOf((connection.timeoutMs ?: 30000).toString()) }
    var host by remember { mutableStateOf(connection.host ?: "") }
    var port by remember { mutableStateOf((connection.port ?: 0).toString()) }
    var username by remember { mutableStateOf(connection.username ?: "") }
    var password by remember { mutableStateOf(connection.password ?: "") }
    var useSsl by remember { mutableStateOf(connection.useSsl ?: false) }
    var useTls by remember { mutableStateOf(connection.useTls ?: false) }
    var clientSecret by remember { mutableStateOf(connection.clientSecret ?: "") }
    var isSaving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Connection (${connection.type})") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                )
                when (connection.type.uppercase()) {
                    "HTTP" -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        // Auth selector
                        Text("Auth Type", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("NONE", "BASIC", "BEARER").forEach { type ->
                                FilterChip(
                                    selected = authType == type,
                                    onClick = { if (!isSaving) authType = type },
                                    label = { Text(type) },
                                )
                            }
                        }
                        when (authType) {
                            "BASIC" -> {
                                OutlinedTextField(
                                    value = httpBasicUsername,
                                    onValueChange = { httpBasicUsername = it },
                                    label = { Text("Username") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isSaving,
                                )
                                OutlinedTextField(
                                    value = httpBasicPassword,
                                    onValueChange = { httpBasicPassword = it },
                                    label = { Text("Password") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isSaving,
                                )
                            }

                            "BEARER" -> {
                                OutlinedTextField(
                                    value = httpBearerToken,
                                    onValueChange = { httpBearerToken = it },
                                    label = { Text("Bearer Token") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isSaving,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = timeoutMs,
                            onValueChange = { timeoutMs = it },
                            label = { Text("Timeout (ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                    }

                    "IMAP", "POP3" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useSsl,
                                onCheckedChange = { if (!isSaving) useSsl = it },
                            )
                            Text("Use SSL")
                        }
                    }

                    "SMTP" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useTls,
                                onCheckedChange = { if (!isSaving) useTls = it },
                            )
                            Text("Use TLS")
                        }
                    }

                    "OAUTH2" -> {
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text("Client Secret") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                        )
                    }
                }

                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Saving…")
                    }
                }
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val req =
                    com.jervis.dto.connection.ConnectionUpdateRequestDto(
                        name = name,
                        baseUrl = if (connection.type.uppercase() == "HTTP") baseUrl else null,
                        authType = if (connection.type.uppercase() == "HTTP") authType else null,
                        httpBasicUsername =
                            if (connection.type.uppercase() == "HTTP" &&
                                authType == "BASIC"
                            ) {
                                httpBasicUsername.ifBlank { null }
                            } else {
                                null
                            },
                        httpBasicPassword =
                            if (connection.type.uppercase() == "HTTP" &&
                                authType == "BASIC"
                            ) {
                                httpBasicPassword.ifBlank { null }
                            } else {
                                null
                            },
                        httpBearerToken =
                            if (connection.type.uppercase() == "HTTP" &&
                                authType == "BEARER"
                            ) {
                                httpBearerToken.ifBlank { null }
                            } else {
                                null
                            },
                        timeoutMs = if (connection.type.uppercase() == "HTTP") timeoutMs.toLongOrNull() else null,
                        host = if (connection.type.uppercase() != "HTTP" && connection.type.uppercase() != "OAUTH2") host else null,
                        port =
                            if (connection.type.uppercase() != "HTTP" &&
                                connection.type.uppercase() != "OAUTH2"
                            ) {
                                port.toIntOrNull()
                            } else {
                                null
                            },
                        username = if (connection.type.uppercase() != "HTTP" && connection.type.uppercase() != "OAUTH2") username else null,
                        password =
                            if (connection.type.uppercase() != "HTTP" &&
                                connection.type.uppercase() != "OAUTH2"
                            ) {
                                password.ifBlank { null }
                            } else {
                                null
                            },
                        clientSecret = if (connection.type.uppercase() == "OAUTH2") clientSecret.ifBlank { null } else null,
                    )
                isSaving = true
                errorText = null
                onSave(req) { ok, err ->
                    if (!ok) {
                        isSaving = false
                        errorText = err ?: "Failed to save"
                    }
                }
            }, enabled = name.isNotBlank() && !isSaving) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectionsTabContent(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshAll() {
        scope.launch {
            loading = true
            error = null
            try {
                connections = repository.connections.listConnections()
                clients = repository.clients.listClients()
                projects = repository.projects.getAllProjects()
            } catch (e: Exception) {
                error = "Failed to load connections: ${e.message}"
                repository.errorLogs.recordUiError(
                    message = error!!,
                    stackTrace = e.toString(),
                    causeType = e::class.simpleName,
                )
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connections", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { refreshAll() }) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> {
                CircularProgressIndicator()
            }

            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            else -> {
                ConnectionsOverviewPanel(
                    connections = connections,
                    clients = clients,
                    projects = projects,
                    onCreateConnection = { req ->
                        scope.launch {
                            try {
                                repository.connections.createConnection(req)
                                refreshAll()
                            } catch (e: Exception) {
                                error = "Failed to create connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                    onTestConnection = { id ->
                        scope.launch {
                            try {
                                val res = repository.connections.testConnection(id)
                                if (res.success) {
                                    repository.connections.updateConnection(
                                        id,
                                        com.jervis.dto.connection.ConnectionUpdateRequestDto(
                                            state = com.jervis.dto.connection.ConnectionStateEnum.VALID,
                                        ),
                                    )
                                }
                                refreshAll()
                            } catch (e: Exception) {
                                error = "Failed to test connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                    onEditConnection = { id, updateReq, onResult ->
                        scope.launch {
                            try {
                                repository.connections.updateConnection(id, updateReq)
                                refreshAll()
                                onResult(true, null)
                            } catch (e: Exception) {
                                error = "Failed to update connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                                onResult(false, e.message)
                            }
                        }
                    },
                    onDuplicate = { conn ->
                        scope.launch {
                            try {
                                val req =
                                    ConnectionCreateRequestDto(
                                        type = conn.type,
                                        name = conn.name + " (copy)",
                                        state = ConnectionStateEnum.NEW,
                                        baseUrl = conn.baseUrl,
                                        authType = conn.authType,
                                        httpBasicUsername = conn.httpBasicUsername,
                                        httpBasicPassword = conn.httpBasicPassword,
                                        httpBearerToken = conn.httpBearerToken,
                                        timeoutMs = conn.timeoutMs,
                                        host = conn.host,
                                        port = conn.port,
                                        username = conn.username,
                                        password = conn.password,
                                        useSsl = conn.useSsl,
                                        useTls = conn.useTls,
                                        authorizationUrl = conn.authorizationUrl,
                                        tokenUrl = conn.tokenUrl,
                                        clientSecret = conn.clientSecret,
                                        redirectUri = conn.redirectUri,
                                        scope = conn.scope,
                                    )
                                repository.connections.createConnection(req)
                                refreshAll()
                            } catch (e: Exception) {
                                error = "Failed to duplicate connection: ${e.message}"
                                repository.errorLogs.recordUiError(
                                    message = error!!,
                                    stackTrace = e.toString(),
                                    causeType = e::class.simpleName,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionQuickCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit,
) {
    var connectionType by remember { mutableStateOf("HTTP") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("NONE") }
    var httpBasicUsername by remember { mutableStateOf("") }
    var httpBasicPassword by remember { mutableStateOf("") }
    var httpBearerToken by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useSsl by remember { mutableStateOf(true) }
    var useTls by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Connection") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection Type:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HTTP", "IMAP", "POP3", "SMTP", "OAUTH2").forEach { type ->
                        FilterChip(
                            selected = connectionType == type,
                            onClick = { connectionType = type },
                            label = { Text(type) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )

                when (connectionType) {
                    "HTTP" -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Auth Type", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("NONE", "BASIC", "BEARER").forEach { type ->
                                FilterChip(
                                    selected = authType == type,
                                    onClick = { authType = type },
                                    label = { Text(type) },
                                )
                            }
                        }
                        when (authType) {
                            "BASIC" -> {
                                OutlinedTextField(
                                    value = httpBasicUsername,
                                    onValueChange = { httpBasicUsername = it },
                                    label = { Text("Username") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = httpBasicPassword,
                                    onValueChange = { httpBasicPassword = it },
                                    label = { Text("Password") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            "BEARER" -> {
                                OutlinedTextField(
                                    value = httpBearerToken,
                                    onValueChange = { httpBearerToken = it },
                                    label = { Text("Bearer Token") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    "IMAP", "POP3" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useSsl, onCheckedChange = { useSsl = it })
                            Text("Use SSL")
                        }
                    }

                    "SMTP" -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useTls, onCheckedChange = { useTls = it })
                            Text("Use TLS")
                        }
                    }

                    "OAUTH2" -> {
                        // Minimal seed – advanced fields can be added later if needed
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Auth Server Base URL (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val req =
                        ConnectionCreateRequestDto(
                            type = connectionType,
                            name = name,
                            baseUrl = if (connectionType == "HTTP") baseUrl else null,
                            authType = if (connectionType == "HTTP") authType else null,
                            httpBasicUsername =
                                if (connectionType == "HTTP" &&
                                    authType == "BASIC"
                                ) {
                                    httpBasicUsername.ifBlank { null }
                                } else {
                                    null
                                },
                            httpBasicPassword =
                                if (connectionType == "HTTP" &&
                                    authType == "BASIC"
                                ) {
                                    httpBasicPassword.ifBlank { null }
                                } else {
                                    null
                                },
                            httpBearerToken =
                                if (connectionType == "HTTP" && authType == "BEARER") {
                                    httpBearerToken.ifBlank {
                                        null
                                    }
                                } else {
                                    null
                                },
                            host = if (connectionType != "HTTP" && connectionType != "OAUTH2") host else null,
                            port = if (connectionType != "HTTP" && connectionType != "OAUTH2") port.toIntOrNull() else null,
                            username = if (connectionType != "HTTP" && connectionType != "OAUTH2") username else null,
                            password = if (connectionType != "HTTP" && connectionType != "OAUTH2") password.ifBlank { null } else null,
                            useSsl = if (connectionType == "IMAP" || connectionType == "POP3") useSsl else null,
                            useTls = if (connectionType == "SMTP") useTls else null,
                        )
                    onCreate(req)
                },
                enabled =
                    name.isNotBlank() && (
                        (connectionType == "HTTP" && baseUrl.isNotBlank()) ||
                            (connectionType == "IMAP" || connectionType == "POP3" || connectionType == "SMTP") && host.isNotBlank() &&
                            port.isNotBlank() &&
                            username.isNotBlank()
                    ),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectEditDialog(
    project: ProjectDto,
    clients: List<ClientDto>,
    connections: List<ConnectionResponseDto>,
    allProjects: List<ProjectDto>,
    onCreateConnection: ((ConnectionCreateRequestDto, (String) -> Unit) -> Unit)? = null,
    onTestConnection: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onUpdateConnection: ((String, com.jervis.dto.connection.ConnectionUpdateRequestDto, (Boolean, String?) -> Unit) -> Unit)? = null,
    onDuplicate: (conn: ConnectionResponseDto, onDuplicated: (newId: String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (ProjectDto) -> Unit,
) {
    var name by remember { mutableStateOf(project.name ?: "") }
    var selectedClientId by remember { mutableStateOf(project.clientId) }
    var clientExpanded by remember { mutableStateOf(false) }
    // Use immutable Set and always assign a new instance to trigger recomposition on change
    var selectedConnectionIds by remember { mutableStateOf(project.connectionIds.toSet()) }
    var showCreateConnectionDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Client:", style = MaterialTheme.typography.labelMedium)

                if (clients.isEmpty()) {
                    Text(
                        "No clients available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = clientExpanded,
                        onExpandedChange = { clientExpanded = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = clients.firstOrNull { it.id == selectedClientId }?.name ?: "Select client",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Client") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = clientExpanded,
                            onDismissRequest = { clientExpanded = false },
                        ) {
                            clients.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        selectedClientId = c.id
                                        clientExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Connections:", style = MaterialTheme.typography.labelMedium)
                    if (onCreateConnection != null) {
                        TextButton(onClick = { showCreateConnectionDialog = true }) { Text("Create Connection") }
                    }
                }
                if (connections.isEmpty()) {
                    Text(
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(connections) { conn ->
                            val ownedByThisProject = project.connectionIds.contains(conn.id)
                            val ownerLabel: String? =
                                when {
                                    ownedByThisProject -> {
                                        "Attached to this project"
                                    }

                                    clients.any { it.connectionIds.contains(conn.id) } -> {
                                        val c = clients.first { it.connectionIds.contains(conn.id) }
                                        "Client: ${c.name}"
                                    }

                                    allProjects.any { it.connectionIds.contains(conn.id) } -> {
                                        val p = allProjects.first { it.connectionIds.contains(conn.id) }
                                        "Project: ${p.name}"
                                    }

                                    else -> {
                                        null
                                    }
                                }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${conn.name} (${conn.type})",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            val subtitle =
                                                buildString {
                                                    when (conn.type.uppercase()) {
                                                        "HTTP" -> {
                                                            append(conn.baseUrl ?: "")
                                                        }

                                                        "IMAP", "POP3", "SMTP" -> {
                                                            append(
                                                                listOfNotNull(
                                                                    conn.host,
                                                                    conn.username,
                                                                ).joinToString(" · "),
                                                            )
                                                        }

                                                        "OAUTH2" -> {
                                                            append(
                                                                listOfNotNull(
                                                                    conn.authorizationUrl,
                                                                ).joinToString(" · "),
                                                            )
                                                        }
                                                    }
                                                }
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                ownerLabel ?: "Unattached",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        if (ownerLabel == null || ownedByThisProject) {
                                            val checked = selectedConnectionIds.contains(conn.id)
                                            // Local states for visible test progress/result per connection row
                                            var testInProgress by remember { mutableStateOf(false) }
                                            var testResult by remember { mutableStateOf<Boolean?>(null) }

                                            Row(
                                                modifier =
                                                    Modifier.clickable {
                                                        selectedConnectionIds =
                                                            if (checked) {
                                                                selectedConnectionIds - conn.id
                                                            } else {
                                                                selectedConnectionIds +
                                                                    conn.id
                                                            }
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { isChecked ->
                                                        selectedConnectionIds =
                                                            if (isChecked) {
                                                                selectedConnectionIds + conn.id
                                                            } else {
                                                                selectedConnectionIds -
                                                                    conn.id
                                                            }
                                                    },
                                                )
                                                Text(if (checked) "Attached" else "Attach")
                                                if (onTestConnection != null) {
                                                    TextButton(onClick = {
                                                        testInProgress = true
                                                        onTestConnection.invoke(conn.id) { ok ->
                                                            testInProgress = false
                                                            testResult = ok
                                                        }
                                                    }) { Text("Test") }
                                                }
                                                if (onUpdateConnection != null) {
                                                    var showEdit by remember { mutableStateOf(false) }
                                                    TextButton(onClick = { showEdit = true }) { Text("Edit") }
                                                    if (showEdit) {
                                                        ConnectionEditDialog(
                                                            connection = conn,
                                                            onDismiss = { showEdit = false },
                                                            onSave = { updateReq, onResult ->
                                                                onUpdateConnection.invoke(
                                                                    conn.id,
                                                                    updateReq,
                                                                ) { ok, err ->
                                                                    if (ok) showEdit = false
                                                                    onResult(ok, err)
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            }

                                            // Testing progress dialog
                                            if (testInProgress) {
                                                AlertDialog(
                                                    onDismissRequest = {},
                                                    title = { Text("Testing connection") },
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                        ) {
                                                            CircularProgressIndicator()
                                                            Text("Running test...")
                                                        }
                                                    },
                                                    confirmButton = {},
                                                )
                                            }

                                            // Testing result dialog
                                            if (testResult != null) {
                                                AlertDialog(
                                                    onDismissRequest = { testResult = null },
                                                    title = { Text(if (testResult == true) "Test passed" else "Test failed") },
                                                    text = {
                                                        Text(
                                                            if (testResult ==
                                                                true
                                                            ) {
                                                                "Connection is valid."
                                                            } else {
                                                                "Connection test failed. Please check configuration and try again."
                                                            },
                                                        )
                                                    },
                                                    confirmButton = {
                                                        TextButton(onClick = { testResult = null }) { Text("OK") }
                                                    },
                                                )
                                            }
                                        } else if (!ownedByThisProject) {
                                            Button(onClick = {
                                                onDuplicate(conn) { newId ->
                                                    selectedConnectionIds = selectedConnectionIds + newId
                                                }
                                            }) { Text("Duplicate") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showCreateConnectionDialog && onCreateConnection != null) {
                        ConnectionQuickCreateDialog(
                            onDismiss = { showCreateConnectionDialog = false },
                            onCreate = { req ->
                                onCreateConnection.invoke(req) { newId ->
                                    selectedConnectionIds = selectedConnectionIds + newId
                                    showCreateConnectionDialog = false
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated =
                        project.copy(
                            name = name,
                            clientId = selectedClientId,
                            connectionIds = selectedConnectionIds.toList(),
                        )
                    onUpdate(updated)
                },
                enabled = name.isNotBlank(),
            ) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Legacy ConnectionEditDialog removed – editing is handled via create/duplicate flows in Client/Project edit
