package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.GitConfigDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.language.LanguageEnum
import com.jervis.repository.JervisRepository
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Clients", "Projects")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
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
                    text = { Text(title) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tab content
        when (selectedTab) {
            0 -> ClientsTabContent(repository)
            1 -> ProjectsTabContent(repository)
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
            verticalAlignment = Alignment.CenterVertically
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
            loading -> CircularProgressIndicator()
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            clients.isEmpty() -> Text("No clients configured")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(clients) { client ->
                        ClientCard(
                            client = client,
                            onClick = {
                                selectedClient = client
                                showEditDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ClientCreateDialog(
            connections = connections,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, connectionId ->
                scope.launch {
                    try {
                        val newClient = ClientDto(
                            name = name,
                            connectionIds = listOfNotNull(connectionId)
                        )
                        repository.clients.createClient(newClient)
                        showCreateDialog = false
                        loadClients()
                    } catch (e: Exception) {
                        error = "Failed to create client: ${e.message}"
                    }
                }
            }
        )
    }

    if (showEditDialog && selectedClient != null) {
        ClientEditDialog(
            client = selectedClient!!,
            connections = connections,
            clients = clients,
            projects = projects,
            onDuplicate = { conn, onDuplicated ->
                scope.launch {
                    try {
                        val req = ConnectionCreateRequestDto(
                            type = conn.type,
                            name = conn.name + " (copy)",
                            state = ConnectionStateEnum.NEW,
                            baseUrl = conn.baseUrl,
                            authType = conn.authType,
                            credentials = conn.credentials,
                            timeoutMs = conn.timeoutMs,
                            host = conn.host,
                            port = conn.port,
                            username = conn.username,
                            password = conn.password,
                            useSsl = conn.useSsl,
                            useTls = conn.useTls,
                            authorizationUrl = conn.authorizationUrl,
                            tokenUrl = conn.tokenUrl,
                            clientId = conn.clientId,
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
                    }
                }
            }
        )
    }
}

@Composable
private fun ClientCard(client: ClientDto, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(client.name, style = MaterialTheme.typography.titleMedium)
            
            if (client.connectionIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connections: ${client.connectionIds.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (client.gitProvider != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Git: ${client.gitProvider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            verticalAlignment = Alignment.CenterVertically
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
            loading -> CircularProgressIndicator()
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            projects.isEmpty() -> Text("No projects configured")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projects) { project ->
                        ProjectCard(
                            project = project,
                            clients = clients,
                            onClick = {
                                selectedProject = project
                                showEditDialog = true
                            }
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
            onCreate = { name, clientId ->
                scope.launch {
                    try {
                        val newProject = ProjectDto(
                            name = name,
                            clientId = clientId
                        )
                        repository.projects.saveProject(newProject)
                        showCreateDialog = false
                        loadProjects()
                    } catch (e: Exception) {
                        error = "Failed to create project: ${e.message}"
                    }
                }
            }
        )
    }

    if (showEditDialog && selectedProject != null) {
        ProjectEditDialog(
            project = selectedProject!!,
            clients = clients,
            connections = connections,
            allProjects = projects,
            onDuplicate = { conn, onDuplicated ->
                scope.launch {
                    try {
                        val req = ConnectionCreateRequestDto(
                            type = conn.type,
                            name = conn.name + " (copy)",
                            state = ConnectionStateEnum.NEW,
                            baseUrl = conn.baseUrl,
                            authType = conn.authType,
                            credentials = conn.credentials,
                            timeoutMs = conn.timeoutMs,
                            host = conn.host,
                            port = conn.port,
                            username = conn.username,
                            password = conn.password,
                            useSsl = conn.useSsl,
                            useTls = conn.useTls,
                            authorizationUrl = conn.authorizationUrl,
                            tokenUrl = conn.tokenUrl,
                            clientId = conn.clientId,
                            clientSecret = conn.clientSecret,
                            redirectUri = conn.redirectUri,
                            scope = conn.scope,
                        )
                        val created = repository.connections.createConnection(req)
                        onDuplicated(created.id)
                        connections = repository.connections.listConnections()
                    } catch (e: Exception) {
                        error = "Failed to duplicate connection: ${e.message}"
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
                        repository.projects.saveProject(updatedProject)
                        showEditDialog = false
                        selectedProject = null
                        loadProjects()
                    } catch (e: Exception) {
                        error = "Failed to update project: ${e.message}"
                    }
                }
            }
        )
    }
}

@Composable
private fun ProjectCard(project: ProjectDto, clients: List<ClientDto>, onClick: () -> Unit = {}) {
    val clientName = clients.firstOrNull { it.id == project.clientId }?.name
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(project.name ?: "Unnamed", style = MaterialTheme.typography.titleMedium)
            
            if (clientName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Client: $clientName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (project.connectionIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connections: ${project.connectionIds.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onCreate: (name: String, connectionId: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Client") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
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
                                    text = { Text("${conn.name} (${conn.type})") },
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
                onClick = { onCreate(name, selectedConnectionId) },
                enabled = name.isNotBlank()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCreateDialog(
    clients: List<ClientDto>,
    onDismiss: () -> Unit,
    onCreate: (name: String, clientId: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Client:", style = MaterialTheme.typography.labelMedium)

                if (clients.isEmpty()) {
                    Text(
                        "No clients available. Create a client first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = clients.firstOrNull { it.id == selectedClientId }?.name ?: "Select client",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Client") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            clients.forEach { client ->
                                DropdownMenuItem(
                                    text = { Text(client.name) },
                                    onClick = {
                                        selectedClientId = client.id
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
                    selectedClientId?.let { onCreate(name, it) }
                },
                enabled = name.isNotBlank() && selectedClientId != null
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

// Legacy connection dialogs removed – connections are now managed inside Client/Project edit

@Composable
private fun ClientEditDialog(
    client: ClientDto,
    connections: List<ConnectionResponseDto>,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onDuplicate: (conn: ConnectionResponseDto, onDuplicated: (newId: String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (ClientDto) -> Unit
) {
    var name by remember { mutableStateOf(client.name) }
    var selectedConnectionIds by remember { mutableStateOf(client.connectionIds.toMutableSet()) }
    var gitProvider by remember { mutableStateOf<GitProviderEnum?>(client.gitProvider) }
    var gitAuthType by remember { mutableStateOf<GitAuthTypeEnum?>(client.gitAuthType) }
    var gitUserName by remember { mutableStateOf(client.gitConfig?.gitUserName ?: "") }
    var gitUserEmail by remember { mutableStateOf(client.gitConfig?.gitUserEmail ?: "") }
    var commitMessageTemplate by remember { mutableStateOf(client.gitConfig?.commitMessageTemplate ?: "") }
    var requireGpgSign by remember { mutableStateOf(client.gitConfig?.requireGpgSign ?: false) }
    var gpgKeyId by remember { mutableStateOf(client.gitConfig?.gpgKeyId ?: "") }
    var requireLinearHistory by remember { mutableStateOf(client.gitConfig?.requireLinearHistory ?: false) }
    var conventionalCommits by remember { mutableStateOf(client.gitConfig?.conventionalCommits ?: false) }
    var commitRulesText by remember { mutableStateOf(client.gitConfig?.commitRules?.entries?.joinToString("\n") { "${'$'}{it.key}=${'$'}{it.value}" } ?: "") }

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Client") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Client Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Git provider & auth type
                Text("Git Settings:", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = gitProvider?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Git Provider") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            DropdownMenuItem(text = { Text("None") }, onClick = { gitProvider = null; providerExpanded = false })
                            GitProviderEnum.entries.forEach { prov ->
                                DropdownMenuItem(text = { Text(prov.name) }, onClick = { gitProvider = prov; providerExpanded = false })
                            }
                        }
                    }
                    ExposedDropdownMenuBox(expanded = authExpanded, onExpandedChange = { authExpanded = it }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = gitAuthType?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Git Auth Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                            DropdownMenuItem(text = { Text("None") }, onClick = { gitAuthType = null; authExpanded = false })
                            GitAuthTypeEnum.entries.forEach { a ->
                                DropdownMenuItem(text = { Text(a.name) }, onClick = { gitAuthType = a; authExpanded = false })
                            }
                        }
                    }
                }

                OutlinedTextField(value = gitUserName, onValueChange = { gitUserName = it }, label = { Text("Git User Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gitUserEmail, onValueChange = { gitUserEmail = it }, label = { Text("Git User Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = commitMessageTemplate, onValueChange = { commitMessageTemplate = it }, label = { Text("Commit Message Template") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requireGpgSign, onCheckedChange = { requireGpgSign = it })
                    Text("Require GPG Sign")
                }
                OutlinedTextField(value = gpgKeyId, onValueChange = { gpgKeyId = it }, label = { Text("GPG Key ID") }, modifier = Modifier.fillMaxWidth())
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
                    modifier = Modifier.fillMaxWidth()
                )

                // Git credentials – dev mode: plaintext visible
                Text("Git Credentials (plaintext):", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = sshPrivateKey, onValueChange = { sshPrivateKey = it }, label = { Text("SSH Private Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sshPublicKey, onValueChange = { sshPublicKey = it }, label = { Text("SSH Public Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sshPassphrase, onValueChange = { sshPassphrase = it }, label = { Text("SSH Passphrase") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = httpsToken, onValueChange = { httpsToken = it }, label = { Text("HTTPS Token") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = httpsUsername, onValueChange = { httpsUsername = it }, label = { Text("HTTPS Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = httpsPassword, onValueChange = { httpsPassword = it }, label = { Text("HTTPS Password") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gpgPrivateKey, onValueChange = { gpgPrivateKey = it }, label = { Text("GPG Private Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gpgPublicKey, onValueChange = { gpgPublicKey = it }, label = { Text("GPG Public Key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gpgPassphrase, onValueChange = { gpgPassphrase = it }, label = { Text("GPG Passphrase") }, modifier = Modifier.fillMaxWidth())

                // Language and last selected project
                ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
                    OutlinedTextField(
                        value = defaultLanguage.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                        LanguageEnum.entries.forEach { lang ->
                            DropdownMenuItem(text = { Text(lang.name) }, onClick = { defaultLanguage = lang; langExpanded = false })
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
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = projectExpanded, onDismissRequest = { projectExpanded = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { lastSelectedProjectId = null; projectExpanded = false })
                        clientProjects.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name ?: "Unnamed") }, onClick = { lastSelectedProjectId = p.id; projectExpanded = false })
                        }
                    }
                }

                Text("Connections:", style = MaterialTheme.typography.labelMedium)

                if (connections.isEmpty()) {
                    Text(
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections) { conn ->
                            val ownedByThisClient = client.connectionIds.contains(conn.id)
                            val ownerLabel: String? = when {
                                ownedByThisClient -> "Attached to this client"
                                clients.any { it.connectionIds.contains(conn.id) } -> {
                                    val c = clients.first { it.connectionIds.contains(conn.id) }
                                    "Client: ${c.name}"
                                }
                                projects.any { it.connectionIds.contains(conn.id) } -> {
                                    val p = projects.first { it.connectionIds.contains(conn.id) }
                                    "Project: ${p.name}"
                                }
                                else -> null
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${conn.name} (${conn.type})", style = MaterialTheme.typography.bodyMedium)
                                            val subtitle = buildString {
                                                when (conn.type.uppercase()) {
                                                    "HTTP" -> append(conn.baseUrl ?: "")
                                                    "IMAP", "POP3", "SMTP" -> append(listOfNotNull(conn.host, conn.username).joinToString(" · "))
                                                    "OAUTH2" -> append(listOfNotNull(conn.authorizationUrl, conn.clientId).joinToString(" · "))
                                                }
                                            }
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                ownerLabel ?: "Unattached",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (ownerLabel == null || ownedByThisClient) {
                                            val checked = selectedConnectionIds.contains(conn.id)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { isChecked ->
                                                        if (isChecked) selectedConnectionIds.add(conn.id) else selectedConnectionIds.remove(conn.id)
                                                    }
                                                )
                                                Text(if (checked) "Attached" else "Attach")
                                            }
                                        } else if (!ownedByThisClient) {
                                            Button(onClick = {
                                                onDuplicate(conn) { newId ->
                                                    selectedConnectionIds.add(newId)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Parse commit rules
                    val commitRules: Map<String, String> = commitRulesText
                        .lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf('=')
                            if (idx <= 0) null else (line.substring(0, idx).trim() to line.substring(idx + 1).trim())
                        }
                        .toMap()

                    val updated = client.copy(
                        name = name,
                        gitProvider = gitProvider,
                        gitAuthType = gitAuthType,
                        gitConfig = GitConfigDto(
                            gitUserName = gitUserName.ifBlank { null },
                            gitUserEmail = gitUserEmail.ifBlank { null },
                            commitMessageTemplate = commitMessageTemplate.ifBlank { null },
                            requireGpgSign = requireGpgSign,
                            gpgKeyId = gpgKeyId.ifBlank { null },
                            requireLinearHistory = requireLinearHistory,
                            conventionalCommits = conventionalCommits,
                            commitRules = commitRules
                        ),
                        gitCredentials = GitCredentialsDto(
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
                        connectionIds = selectedConnectionIds.toList()
                    )
                    onUpdate(updated)
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
private fun ProjectEditDialog(
    project: ProjectDto,
    clients: List<ClientDto>,
    connections: List<ConnectionResponseDto>,
    allProjects: List<ProjectDto>,
    onDuplicate: (conn: ConnectionResponseDto, onDuplicated: (newId: String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (ProjectDto) -> Unit
) {
    var name by remember { mutableStateOf(project.name ?: "") }
    var selectedClientId by remember { mutableStateOf(project.clientId) }
    var clientExpanded by remember { mutableStateOf(false) }
    var selectedConnectionIds by remember { mutableStateOf(project.connectionIds.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Project") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Client:", style = MaterialTheme.typography.labelMedium)

                if (clients.isEmpty()) {
                    Text(
                        "No clients available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = clientExpanded,
                        onExpandedChange = { clientExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = clients.firstOrNull { it.id == selectedClientId }?.name ?: "Select client",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Client") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = clientExpanded,
                            onDismissRequest = { clientExpanded = false }
                        ) {
                            clients.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        selectedClientId = c.id
                                        clientExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Text("Connections:", style = MaterialTheme.typography.labelMedium)
                if (connections.isEmpty()) {
                    Text(
                        "No connections available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections) { conn ->
                            val ownedByThisProject = project.connectionIds.contains(conn.id)
                            val ownerLabel: String? = when {
                                ownedByThisProject -> "Attached to this project"
                                clients.any { it.connectionIds.contains(conn.id) } -> {
                                    val c = clients.first { it.connectionIds.contains(conn.id) }
                                    "Client: ${c.name}"
                                }
                                allProjects.any { it.connectionIds.contains(conn.id) } -> {
                                    val p = allProjects.first { it.connectionIds.contains(conn.id) }
                                    "Project: ${p.name}"
                                }
                                else -> null
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${conn.name} (${conn.type})", style = MaterialTheme.typography.bodyMedium)
                                            val subtitle = buildString {
                                                when (conn.type.uppercase()) {
                                                    "HTTP" -> append(conn.baseUrl ?: "")
                                                    "IMAP", "POP3", "SMTP" -> append(listOfNotNull(conn.host, conn.username).joinToString(" · "))
                                                    "OAUTH2" -> append(listOfNotNull(conn.authorizationUrl, conn.clientId).joinToString(" · "))
                                                }
                                            }
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                ownerLabel ?: "Unattached",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (ownerLabel == null || ownedByThisProject) {
                                            val checked = selectedConnectionIds.contains(conn.id)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { isChecked ->
                                                        if (isChecked) selectedConnectionIds.add(conn.id) else selectedConnectionIds.remove(conn.id)
                                                    }
                                                )
                                                Text(if (checked) "Attached" else "Attach")
                                            }
                                        } else if (!ownedByThisProject) {
                                            Button(onClick = {
                                                onDuplicate(conn) { newId ->
                                                    selectedConnectionIds.add(newId)
                                                }
                                            }) { Text("Duplicate") }
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
            Button(
                onClick = {
                    val updated = project.copy(
                        name = name,
                        clientId = selectedClientId,
                        connectionIds = selectedConnectionIds.toList()
                    )
                    onUpdate(updated)
                },
                enabled = name.isNotBlank()
            ) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Legacy ConnectionEditDialog removed – editing is handled via create/duplicate flows in Client/Project edit
