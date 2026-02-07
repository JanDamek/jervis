package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientConnectionCapabilityDto
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectConnectionCapabilityDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun ClientsSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    val scope = rememberCoroutineScope()

    fun loadClients() {
        scope.launch {
            isLoading = true
            try {
                clients = repository.clients.getAllClients()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadClients() }

    JListDetailLayout(
        items = clients,
        selectedItem = selectedClient,
        isLoading = isLoading,
        onItemSelected = { selectedClient = it },
        emptyMessage = "Žádní klienti nenalezeni",
        emptyIcon = "🏢",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadClients() })
                JPrimaryButton(onClick = { /* New client */ }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Přidat klienta")
                }
            }
        },
        listItem = { client ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedClient = client },
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(client.name, style = MaterialTheme.typography.titleMedium)
                        Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        detailContent = { client ->
            ClientEditForm(
                client = client,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.clients.updateClient(updated.id, updated)
                            selectedClient = null
                            loadClients()
                        } catch (_: Exception) {
                        }
                    }
                },
                onCancel = { selectedClient = null },
            )
        },
    )
}

@Composable
private fun ClientEditForm(
    client: ClientDto,
    repository: JervisRepository,
    onSave: (ClientDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(client.name) }
    var description by remember { mutableStateOf(client.description ?: "") }

    // Git commit configuration
    var gitCommitMessageFormat by remember { mutableStateOf(client.gitCommitMessageFormat ?: "") }
    var gitCommitAuthorName by remember { mutableStateOf(client.gitCommitAuthorName ?: "") }
    var gitCommitAuthorEmail by remember { mutableStateOf(client.gitCommitAuthorEmail ?: "") }
    var gitCommitCommitterName by remember { mutableStateOf(client.gitCommitCommitterName ?: "") }
    var gitCommitCommitterEmail by remember { mutableStateOf(client.gitCommitCommitterEmail ?: "") }
    var gitCommitGpgSign by remember { mutableStateOf(client.gitCommitGpgSign) }
    var gitCommitGpgKeyId by remember { mutableStateOf(client.gitCommitGpgKeyId ?: "") }

    // Connections
    var selectedConnectionIds by remember { mutableStateOf(client.connectionIds.toMutableSet()) }
    var availableConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var showConnectionsDialog by remember { mutableStateOf(false) }

    // Capability configuration
    var connectionCapabilities by remember {
        mutableStateOf(client.connectionCapabilities.toMutableList())
    }
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Projects
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadConnections() {
        scope.launch {
            try {
                availableConnections = repository.connections.getAllConnections()
            } catch (_: Exception) {
            }
        }
    }

    fun loadProjects() {
        scope.launch {
            try {
                projects = repository.projects.listProjectsForClient(client.id)
            } catch (_: Exception) {
            }
        }
    }

    fun loadResourcesForCapability(connectionId: String, capability: ConnectionCapability) {
        val key = Pair(connectionId, capability)
        if (key in availableResources || key in loadingResources) return

        scope.launch {
            loadingResources = loadingResources + key
            try {
                val resources = repository.connections.listAvailableResources(connectionId, capability)
                availableResources = availableResources + (key to resources)
            } catch (_: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    fun getCapabilityConfig(connectionId: String, capability: ConnectionCapability): ClientConnectionCapabilityDto? {
        return connectionCapabilities.find { it.connectionId == connectionId && it.capability == capability }
    }

    fun updateCapabilityConfig(config: ClientConnectionCapabilityDto) {
        connectionCapabilities = connectionCapabilities
            .filter { !(it.connectionId == config.connectionId && it.capability == config.capability) }
            .toMutableList()
            .apply { add(config) }
    }

    fun removeCapabilityConfig(connectionId: String, capability: ConnectionCapability) {
        connectionCapabilities = connectionCapabilities
            .filter { !(it.connectionId == connectionId && it.capability == capability) }
            .toMutableList()
    }

    LaunchedEffect(Unit) {
        loadConnections()
        loadProjects()
    }

    LaunchedEffect(availableConnections, selectedConnectionIds.size) {
        selectedConnectionIds.forEach { connId ->
            val connection = availableConnections.firstOrNull { it.id == connId }
            connection?.capabilities?.forEach { capability ->
                loadResourcesForCapability(connId, capability)
            }
        }
    }

    fun findLinkedProject(connectionId: String, capability: ConnectionCapability, resourceId: String): ProjectDto? {
        return projects.find { project ->
            project.resources.any { res ->
                res.connectionId == connectionId &&
                    res.capability == capability &&
                    res.resourceIdentifier == resourceId
            }
        }
    }

    JDetailScreen(
        title = client.name,
        onBack = onCancel,
        onSave = {
            onSave(
                client.copy(
                    name = name,
                    description = description.ifBlank { null },
                    connectionIds = selectedConnectionIds.toList(),
                    connectionCapabilities = connectionCapabilities,
                    gitCommitMessageFormat = gitCommitMessageFormat.ifBlank { null },
                    gitCommitAuthorName = gitCommitAuthorName.ifBlank { null },
                    gitCommitAuthorEmail = gitCommitAuthorEmail.ifBlank { null },
                    gitCommitCommitterName = gitCommitCommitterName.ifBlank { null },
                    gitCommitCommitterEmail = gitCommitCommitterEmail.ifBlank { null },
                    gitCommitGpgSign = gitCommitGpgSign,
                    gitCommitGpgKeyId = gitCommitGpgKeyId.ifBlank { null },
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
            JSection(title = "Základní údaje") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název klienta") },
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

            JSection(title = "Připojení klienta") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Přiřaďte connections tomuto klientovi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    JPrimaryButton(onClick = { showConnectionsDialog = true }) {
                        Text("+ Přidat")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (selectedConnectionIds.isEmpty()) {
                    Text(
                        "Žádná připojení nejsou přiřazena.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    selectedConnectionIds.forEach { connId ->
                        val connection = availableConnections.firstOrNull { it.id == connId }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .heightIn(min = JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        connection?.name ?: "Unknown",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        connection?.protocol?.name ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { selectedConnectionIds.remove(connId) },
                                    modifier = Modifier.size(JervisSpacing.touchTarget),
                                ) {
                                    Text("✕", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (showConnectionsDialog) {
                AlertDialog(
                    onDismissRequest = { showConnectionsDialog = false },
                    title = { Text("Vybrat připojení") },
                    text = {
                        LazyColumn {
                            items(availableConnections.filter { it.id !in selectedConnectionIds }) { conn ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedConnectionIds.add(conn.id)
                                            showConnectionsDialog = false
                                        }
                                        .padding(12.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(conn.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(conn.protocol.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showConnectionsDialog = false }) {
                            Text("Zavřít")
                        }
                    },
                )
            }

            // Capability configuration section
            JSection(title = "Konfigurace schopností") {
                Text(
                    "Nastavte, které zdroje z připojení se mají indexovat pro tohoto klienta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                if (selectedConnectionIds.isEmpty()) {
                    Text(
                        "Nejprve přiřaďte alespoň jedno připojení.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    selectedConnectionIds.forEach { connId ->
                        val connection = availableConnections.firstOrNull { it.id == connId }
                        if (connection != null && connection.capabilities.isNotEmpty()) {
                            ConnectionCapabilityCard(
                                connection = connection,
                                capabilities = connectionCapabilities,
                                availableResources = availableResources,
                                loadingResources = loadingResources,
                                onLoadResources = { capability -> loadResourcesForCapability(connId, capability) },
                                onUpdateConfig = { config -> updateCapabilityConfig(config) },
                                onRemoveConfig = { capability -> removeCapabilityConfig(connId, capability) },
                                getConfig = { capability -> getCapabilityConfig(connId, capability) },
                            )
                        }
                    }
                }
            }

            // Provider resources → project creation section
            JSection(title = "Dostupné zdroje z providerů") {
                Text(
                    "Zdroje z připojených služeb. Můžete vytvořit projekt propojený s daným zdrojem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                if (selectedConnectionIds.isEmpty()) {
                    Text(
                        "Nejprve přiřaďte alespoň jedno připojení.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    selectedConnectionIds.forEach { connId ->
                        val connection = availableConnections.firstOrNull { it.id == connId }
                        if (connection != null && connection.capabilities.isNotEmpty()) {
                            ProviderResourcesCard(
                                connection = connection,
                                availableResources = availableResources,
                                loadingResources = loadingResources,
                                findLinkedProject = { capability, resourceId ->
                                    findLinkedProject(connId, capability, resourceId)
                                },
                                onCreateProject = { resource, capability ->
                                    scope.launch {
                                        try {
                                            repository.projects.saveProject(
                                                ProjectDto(
                                                    name = resource.name,
                                                    description = resource.description,
                                                    clientId = client.id,
                                                    connectionCapabilities = listOf(
                                                        ProjectConnectionCapabilityDto(
                                                            connectionId = connId,
                                                            capability = capability,
                                                            enabled = true,
                                                            resourceIdentifier = resource.id,
                                                            selectedResources = listOf(resource.id),
                                                        ),
                                                    ),
                                                    resources = listOf(
                                                        ProjectResourceDto(
                                                            connectionId = connId,
                                                            capability = capability,
                                                            resourceIdentifier = resource.id,
                                                            displayName = resource.name,
                                                        ),
                                                    ),
                                                ),
                                            )
                                            loadProjects()
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            JSection(title = "Projekty klienta") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Projekty přiřazené tomuto klientovi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    JPrimaryButton(onClick = { showCreateProjectDialog = true }) {
                        Text("+ Vytvořit projekt")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (projects.isEmpty()) {
                    Text(
                        "Žádné projekty nejsou vytvořeny.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    projects.forEach { project ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    // TODO: Navigate to project detail
                                },
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .heightIn(min = JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        project.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    project.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    }
                }
            }

            if (showCreateProjectDialog) {
                var newProjectName by remember { mutableStateOf("") }
                var newProjectDescription by remember { mutableStateOf("") }

                AlertDialog(
                    onDismissRequest = { showCreateProjectDialog = false },
                    title = { Text("Vytvořit nový projekt") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = newProjectName,
                                onValueChange = { newProjectName = it },
                                label = { Text("Název projektu") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newProjectDescription,
                                onValueChange = { newProjectDescription = it },
                                label = { Text("Popis (volitelné)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        repository.projects.saveProject(
                                            ProjectDto(
                                                name = newProjectName,
                                                description = newProjectDescription.ifBlank { null },
                                                clientId = client.id,
                                            ),
                                        )
                                        loadProjects()
                                        showCreateProjectDialog = false
                                    } catch (_: Exception) {
                                    }
                                }
                            },
                            enabled = newProjectName.isNotBlank(),
                        ) {
                            Text("Vytvořit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateProjectDialog = false }) {
                            Text("Zrušit")
                        }
                    },
                )
            }

            JSection(title = "Výchozí Git Commit Konfigurace") {
                Text(
                    "Tato konfigurace bude použita pro všechny projekty klienta (pokud projekt nepřepíše).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                GitCommitConfigFields(
                    messageFormat = gitCommitMessageFormat,
                    onMessageFormatChange = { gitCommitMessageFormat = it },
                    authorName = gitCommitAuthorName,
                    onAuthorNameChange = { gitCommitAuthorName = it },
                    authorEmail = gitCommitAuthorEmail,
                    onAuthorEmailChange = { gitCommitAuthorEmail = it },
                    committerName = gitCommitCommitterName,
                    onCommitterNameChange = { gitCommitCommitterName = it },
                    committerEmail = gitCommitCommitterEmail,
                    onCommitterEmailChange = { gitCommitCommitterEmail = it },
                    gpgSign = gitCommitGpgSign,
                    onGpgSignChange = { gitCommitGpgSign = it },
                    gpgKeyId = gitCommitGpgKeyId,
                    onGpgKeyIdChange = { gitCommitGpgKeyId = it },
                )
            }

            // Bottom spacing
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConnectionCapabilityCard(
    connection: ConnectionResponseDto,
    capabilities: List<ClientConnectionCapabilityDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    onLoadResources: (ConnectionCapability) -> Unit,
    onUpdateConfig: (ClientConnectionCapabilityDto) -> Unit,
    onRemoveConfig: (ConnectionCapability) -> Unit,
    getConfig: (ConnectionCapability) -> ClientConnectionCapabilityDto?,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = JervisSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📌", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        connection.capabilities.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                connection.capabilities.forEach { capability ->
                    CapabilityConfigItem(
                        connectionId = connection.id,
                        capability = capability,
                        config = getConfig(capability),
                        resources = availableResources[Pair(connection.id, capability)] ?: emptyList(),
                        isLoadingResources = Pair(connection.id, capability) in loadingResources,
                        onLoadResources = { onLoadResources(capability) },
                        onUpdateConfig = onUpdateConfig,
                        onRemoveConfig = { onRemoveConfig(capability) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CapabilityConfigItem(
    connectionId: String,
    capability: ConnectionCapability,
    config: ClientConnectionCapabilityDto?,
    resources: List<ConnectionResourceDto>,
    isLoadingResources: Boolean,
    onLoadResources: () -> Unit,
    onUpdateConfig: (ClientConnectionCapabilityDto) -> Unit,
    onRemoveConfig: () -> Unit,
) {
    val isEnabled = config?.enabled ?: false
    val indexAllResources = config?.indexAllResources ?: true
    val selectedResources = config?.selectedResources ?: emptyList()

    LaunchedEffect(isEnabled, indexAllResources) {
        if (isEnabled && !indexAllResources && resources.isEmpty()) {
            onLoadResources()
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        onUpdateConfig(
                            ClientConnectionCapabilityDto(
                                connectionId = connectionId,
                                capability = capability,
                                enabled = true,
                                indexAllResources = true,
                                selectedResources = emptyList(),
                            ),
                        )
                    } else {
                        onRemoveConfig()
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                getCapabilityLabel(capability),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (isEnabled) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = JervisSpacing.touchTarget)
                        .clickable {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = true,
                                    selectedResources = emptyList(),
                                ),
                            )
                        },
                ) {
                    RadioButton(
                        selected = indexAllResources,
                        onClick = {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = true,
                                    selectedResources = emptyList(),
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        getIndexAllLabel(capability),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = JervisSpacing.touchTarget)
                        .clickable {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = false,
                                    selectedResources = selectedResources,
                                ),
                            )
                            onLoadResources()
                        },
                ) {
                    RadioButton(
                        selected = !indexAllResources,
                        onClick = {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = false,
                                    selectedResources = selectedResources,
                                ),
                            )
                            onLoadResources()
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Pouze vybrané:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!indexAllResources) {
                    var resourceFilter by remember { mutableStateOf("") }

                    Column(modifier = Modifier.padding(start = 32.dp, top = 4.dp)) {
                        if (isLoadingResources) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Načítám dostupné zdroje...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (resources.isEmpty()) {
                            Text(
                                "Žádné zdroje k dispozici.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (resources.size > 5) {
                                OutlinedTextField(
                                    value = resourceFilter,
                                    onValueChange = { resourceFilter = it },
                                    label = { Text("Filtrovat...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(4.dp))
                            }

                            val sortedFiltered = resources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    resourceFilter.isBlank() ||
                                        res.name.contains(resourceFilter, ignoreCase = true) ||
                                        res.id.contains(resourceFilter, ignoreCase = true) ||
                                        (res.description?.contains(resourceFilter, ignoreCase = true) == true)
                                }

                            sortedFiltered.forEach { resource ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                ) {
                                    Checkbox(
                                        checked = resource.id in selectedResources,
                                        onCheckedChange = { checked ->
                                            val newSelected = if (checked) {
                                                selectedResources + resource.id
                                            } else {
                                                selectedResources - resource.id
                                            }
                                            onUpdateConfig(
                                                ClientConnectionCapabilityDto(
                                                    connectionId = connectionId,
                                                    capability = capability,
                                                    enabled = true,
                                                    indexAllResources = false,
                                                    selectedResources = newSelected,
                                                ),
                                            )
                                        },
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            resource.name,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        resource.description?.let { desc ->
                                            Text(
                                                desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderResourcesCard(
    connection: ConnectionResponseDto,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    findLinkedProject: (ConnectionCapability, String) -> ProjectDto?,
    onCreateProject: (ConnectionResourceDto, ConnectionCapability) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    val totalResources = connection.capabilities.sumOf { cap ->
        availableResources[Pair(connection.id, cap)]?.size ?: 0
    }
    val isAnyLoading = connection.capabilities.any { cap ->
        Pair(connection.id, cap) in loadingResources
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = JervisSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${connection.provider.name} · $totalResources zdrojů",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isAnyLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                var providerResourceFilter by remember { mutableStateOf("") }
                val allResourceCount = connection.capabilities.sumOf { cap ->
                    (availableResources[Pair(connection.id, cap)] ?: emptyList()).size
                }

                if (allResourceCount > 5) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerResourceFilter,
                        onValueChange = { providerResourceFilter = it },
                        label = { Text("Filtrovat zdroje...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                connection.capabilities.forEach { capability ->
                    val key = Pair(connection.id, capability)
                    val resources = availableResources[key] ?: emptyList()
                    val isLoading = key in loadingResources

                    val sortedFiltered = resources
                        .sortedBy { it.name.lowercase() }
                        .filter { res ->
                            providerResourceFilter.isBlank() ||
                                res.name.contains(providerResourceFilter, ignoreCase = true) ||
                                res.id.contains(providerResourceFilter, ignoreCase = true) ||
                                (res.description?.contains(providerResourceFilter, ignoreCase = true) == true)
                        }

                    if (isLoading && resources.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Načítám ${getCapabilityLabel(capability)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (sortedFiltered.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            getCapabilityLabel(capability),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))

                        sortedFiltered.forEach { resource ->
                            val linkedProject = findLinkedProject(capability, resource.id)
                            ProviderResourceRow(
                                resource = resource,
                                linkedProject = linkedProject,
                                onCreateProject = { onCreateProject(resource, capability) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderResourceRow(
    resource: ConnectionResourceDto,
    linkedProject: ProjectDto?,
    onCreateProject: () -> Unit,
) {
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(linkedProject) {
        if (linkedProject != null) isCreating = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .heightIn(min = JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(resource.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                resource.id + (resource.description?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (linkedProject != null) {
            Text(
                linkedProject.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(
                onClick = {
                    isCreating = true
                    onCreateProject()
                },
                enabled = !isCreating,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Vytvořit", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────────

internal fun getCapabilityLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Bug Tracker"
        ConnectionCapability.WIKI -> "Wiki"
        ConnectionCapability.REPOSITORY -> "Repository"
        ConnectionCapability.EMAIL_READ -> "Email (Read)"
        ConnectionCapability.EMAIL_SEND -> "Email (Send)"
    }
}

internal fun getIndexAllLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Indexovat všechny projekty"
        ConnectionCapability.WIKI -> "Indexovat všechny prostory"
        ConnectionCapability.EMAIL_READ -> "Indexovat celou schránku"
        ConnectionCapability.EMAIL_SEND -> "Použít všechny odesílatele"
        ConnectionCapability.REPOSITORY -> "Indexovat všechny repozitáře"
    }
}

/**
 * Shared Git commit configuration form fields.
 * Used by both ClientEditForm and ProjectEditForm to avoid duplication.
 */
@Composable
internal fun GitCommitConfigFields(
    messageFormat: String,
    onMessageFormatChange: (String) -> Unit,
    authorName: String,
    onAuthorNameChange: (String) -> Unit,
    authorEmail: String,
    onAuthorEmailChange: (String) -> Unit,
    committerName: String,
    onCommitterNameChange: (String) -> Unit,
    committerEmail: String,
    onCommitterEmailChange: (String) -> Unit,
    gpgSign: Boolean,
    onGpgSignChange: (Boolean) -> Unit,
    gpgKeyId: String,
    onGpgKeyIdChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = messageFormat,
        onValueChange = onMessageFormatChange,
        label = { Text("Formát commit message (volitelné)") },
        placeholder = { Text("[{project}] {message}") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    OutlinedTextField(
        value = authorName,
        onValueChange = onAuthorNameChange,
        label = { Text("Jméno autora") },
        placeholder = { Text("Agent Name") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    OutlinedTextField(
        value = authorEmail,
        onValueChange = onAuthorEmailChange,
        label = { Text("Email autora") },
        placeholder = { Text("agent@example.com") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))

    Text(
        "Committer (ponechte prázdné pro použití autora)",
        style = MaterialTheme.typography.labelMedium,
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    OutlinedTextField(
        value = committerName,
        onValueChange = onCommitterNameChange,
        label = { Text("Jméno committera (volitelné)") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(JervisSpacing.itemGap))

    OutlinedTextField(
        value = committerEmail,
        onValueChange = onCommitterEmailChange,
        label = { Text("Email committera (volitelné)") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = gpgSign,
            onCheckedChange = onGpgSignChange,
        )
        Spacer(Modifier.width(8.dp))
        Text("GPG podpis commitů")
    }

    if (gpgSign) {
        Spacer(Modifier.height(JervisSpacing.itemGap))
        OutlinedTextField(
            value = gpgKeyId,
            onValueChange = onGpgKeyIdChange,
            label = { Text("GPG Key ID") },
            placeholder = { Text("např. ABCD1234") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
