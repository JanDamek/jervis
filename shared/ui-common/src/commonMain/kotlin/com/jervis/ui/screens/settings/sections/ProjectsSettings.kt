package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectConnectionCapabilityDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun ProjectsSettings(repository: JervisRepository) {
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                projects = repository.projects.getAllProjects()
            } catch (e: Exception) {
                // Error handling
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (selectedProject != null) {
        ProjectEditForm(
            project = selectedProject!!,
            repository = repository,
            onSave = { updated ->
                scope.launch {
                     try {
                         repository.projects.updateProject(updated.id ?: "", updated)
                         selectedProject = null
                         loadData()
                     } catch (e: Exception) {
                        // Error handling
                    }
                }
            },
            onCancel = { selectedProject = null }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading && projects.isEmpty()) {
                JCenteredLoading()
            } else if (projects.isEmpty()) {
                JEmptyState(message = "Žádné projekty nenalezeny")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(projects) { project ->
                        JTableRowCard(
                            selected = false,
                            modifier = Modifier.clickable { selectedProject = project }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                                    Text(project.description ?: "Bez popisu", style = MaterialTheme.typography.bodySmall)
                                    if (project.gitCommitAuthorName != null) {
                                        Text(
                                            "Git: ${project.gitCommitAuthorName} <${project.gitCommitAuthorEmail}>",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditForm(
    project: ProjectDto,
    repository: JervisRepository,
    onSave: (ProjectDto) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description ?: "") }

    // Client and connections
    var client by remember { mutableStateOf<ClientDto?>(null) }
    var clientConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }

    // Connection capabilities (new approach)
    var connectionCapabilities by remember {
        mutableStateOf(project.connectionCapabilities.toMutableList())
    }
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Git commit configuration (can override client's config)
    var useCustomGitConfig by remember { mutableStateOf(
        project.gitCommitAuthorName != null || project.gitCommitMessageFormat != null
    ) }
    var gitCommitMessageFormat by remember { mutableStateOf(project.gitCommitMessageFormat ?: "") }
    var gitCommitAuthorName by remember { mutableStateOf(project.gitCommitAuthorName ?: "") }
    var gitCommitAuthorEmail by remember { mutableStateOf(project.gitCommitAuthorEmail ?: "") }
    var gitCommitCommitterName by remember { mutableStateOf(project.gitCommitCommitterName ?: "") }
    var gitCommitCommitterEmail by remember { mutableStateOf(project.gitCommitCommitterEmail ?: "") }
    var gitCommitGpgSign by remember { mutableStateOf(project.gitCommitGpgSign ?: false) }
    var gitCommitGpgKeyId by remember { mutableStateOf(project.gitCommitGpgKeyId ?: "") }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Load client and connections
    LaunchedEffect(project.clientId) {
        try {
            val cid = project.clientId
            if (cid != null) {
                client = repository.clients.getClientById(cid)
                val allConnections = repository.connections.getAllConnections()
                clientConnections = allConnections.filter { conn ->
                    client?.connectionIds?.contains(conn.id) == true
                }
            }
        } catch (e: Exception) {
            // Error handling
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
            } catch (e: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    fun getCapabilityConfig(connectionId: String, capability: ConnectionCapability): ProjectConnectionCapabilityDto? {
        return connectionCapabilities.find { it.connectionId == connectionId && it.capability == capability }
    }

    fun updateCapabilityConfig(config: ProjectConnectionCapabilityDto) {
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

    // Get client's capability config for inheritance display
    fun getClientCapabilityConfig(connectionId: String, capability: ConnectionCapability) =
        client?.connectionCapabilities?.find { it.connectionId == connectionId && it.capability == capability }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(end = 16.dp)
        ) {
            JSection(title = "Základní informace") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název projektu") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            Spacer(Modifier.height(16.dp))

            // Connection capabilities section (new approach)
            JSection(title = "Konfigurace schopností projektu") {
                Text(
                    "Nastavte, které zdroje z připojení klienta budou použity pro tento projekt. " +
                        "Pokud schopnost není nastavena, dědí se z klienta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                if (clientConnections.isEmpty()) {
                    Text(
                        "Načítám connections klienta...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    clientConnections.forEach { connection ->
                        if (connection.capabilities.isNotEmpty()) {
                            ProjectConnectionCapabilityCard(
                                connection = connection,
                                projectCapabilities = connectionCapabilities,
                                clientCapabilities = client?.connectionCapabilities ?: emptyList(),
                                availableResources = availableResources,
                                loadingResources = loadingResources,
                                onLoadResources = { capability -> loadResourcesForCapability(connection.id, capability) },
                                onUpdateConfig = { config -> updateCapabilityConfig(config) },
                                onRemoveConfig = { capability -> removeCapabilityConfig(connection.id, capability) },
                                getConfig = { capability -> getCapabilityConfig(connection.id, capability) },
                                getClientConfig = { capability -> getClientCapabilityConfig(connection.id, capability) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            JSection(title = "Přepsání Git Commit Konfigurace") {
                Text(
                    "Standardně se používá konfigurace z klienta. Zde můžete přepsat pro tento projekt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useCustomGitConfig,
                        onCheckedChange = { useCustomGitConfig = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Přepsat konfiguraci klienta")
                }

                if (useCustomGitConfig) {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Vlastní konfigurace pro tento projekt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = gitCommitMessageFormat,
                        onValueChange = { gitCommitMessageFormat = it },
                        label = { Text("Formát commit message (volitelné)") },
                        placeholder = { Text("[{project}] {message}") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitAuthorName,
                        onValueChange = { gitCommitAuthorName = it },
                        label = { Text("Jméno autora") },
                        placeholder = { Text("Agent Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitAuthorEmail,
                        onValueChange = { gitCommitAuthorEmail = it },
                        label = { Text("Email autora") },
                        placeholder = { Text("agent@example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Committer (ponechte prázdné pro použití autora)",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitCommitterName,
                        onValueChange = { gitCommitCommitterName = it },
                        label = { Text("Jméno committera (volitelné)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitCommitterEmail,
                        onValueChange = { gitCommitCommitterEmail = it },
                        label = { Text("Email committera (volitelné)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = gitCommitGpgSign,
                            onCheckedChange = { gitCommitGpgSign = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("GPG podpis commitů")
                    }

                    if (gitCommitGpgSign) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = gitCommitGpgKeyId,
                            onValueChange = { gitCommitGpgKeyId = it },
                            label = { Text("GPG Key ID") },
                            placeholder = { Text("např. ABCD1234") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        JActionBar {
            TextButton(onClick = onCancel) {
                Text("Zrušit")
            }
            Button(
                onClick = {
                    onSave(
                        project.copy(
                            name = name,
                            description = description.ifBlank { null },
                            connectionCapabilities = connectionCapabilities,
                            gitCommitMessageFormat = if (useCustomGitConfig) gitCommitMessageFormat.ifBlank { null } else null,
                            gitCommitAuthorName = if (useCustomGitConfig) gitCommitAuthorName.ifBlank { null } else null,
                            gitCommitAuthorEmail = if (useCustomGitConfig) gitCommitAuthorEmail.ifBlank { null } else null,
                            gitCommitCommitterName = if (useCustomGitConfig) gitCommitCommitterName.ifBlank { null } else null,
                            gitCommitCommitterEmail = if (useCustomGitConfig) gitCommitCommitterEmail.ifBlank { null } else null,
                            gitCommitGpgSign = if (useCustomGitConfig) gitCommitGpgSign else null,
                            gitCommitGpgKeyId = if (useCustomGitConfig) gitCommitGpgKeyId.ifBlank { null } else null
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Uložit")
            }
        }
    }
}

@Composable
private fun ProjectConnectionCapabilityCard(
    connection: ConnectionResponseDto,
    projectCapabilities: List<ProjectConnectionCapabilityDto>,
    clientCapabilities: List<com.jervis.dto.ClientConnectionCapabilityDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    onLoadResources: (ConnectionCapability) -> Unit,
    onUpdateConfig: (ProjectConnectionCapabilityDto) -> Unit,
    onRemoveConfig: (ConnectionCapability) -> Unit,
    getConfig: (ConnectionCapability) -> ProjectConnectionCapabilityDto?,
    getClientConfig: (ConnectionCapability) -> com.jervis.dto.ClientConnectionCapabilityDto?
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📌", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        connection.capabilities.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                connection.capabilities.forEach { capability ->
                    ProjectCapabilityConfigItem(
                        connectionId = connection.id,
                        capability = capability,
                        config = getConfig(capability),
                        clientConfig = getClientConfig(capability),
                        resources = availableResources[Pair(connection.id, capability)] ?: emptyList(),
                        isLoadingResources = Pair(connection.id, capability) in loadingResources,
                        onLoadResources = { onLoadResources(capability) },
                        onUpdateConfig = onUpdateConfig,
                        onRemoveConfig = { onRemoveConfig(capability) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCapabilityConfigItem(
    connectionId: String,
    capability: ConnectionCapability,
    config: ProjectConnectionCapabilityDto?,
    clientConfig: com.jervis.dto.ClientConnectionCapabilityDto?,
    resources: List<ConnectionResourceDto>,
    isLoadingResources: Boolean,
    onLoadResources: () -> Unit,
    onUpdateConfig: (ProjectConnectionCapabilityDto) -> Unit,
    onRemoveConfig: () -> Unit
) {
    val hasProjectOverride = config != null
    val isEnabled = config?.enabled ?: false
    val selectedResource = config?.resourceIdentifier

    // Load resources when expanding
    LaunchedEffect(hasProjectOverride) {
        if (hasProjectOverride && resources.isEmpty()) {
            onLoadResources()
        }
    }

    Column {
        // Show inheritance info
        if (clientConfig != null) {
            val clientResourceDisplay = if (clientConfig.indexAllResources) {
                "Všechny zdroje"
            } else {
                clientConfig.selectedResources.joinToString(", ").ifEmpty { "Žádné zdroje" }
            }
            Text(
                "Zděděno z klienta: $clientResourceDisplay",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = hasProjectOverride,
                onCheckedChange = { override ->
                    if (override) {
                        onUpdateConfig(
                            ProjectConnectionCapabilityDto(
                                connectionId = connectionId,
                                capability = capability,
                                enabled = true,
                                resourceIdentifier = null,
                                selectedResources = emptyList()
                            )
                        )
                        onLoadResources()
                    } else {
                        onRemoveConfig()
                    }
                }
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Přepsat: ${getCapabilityLabel(capability)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (hasProjectOverride) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                // Resource selection dropdown
                var resourceExpanded by remember { mutableStateOf(false) }

                Text(
                    "Vyberte konkrétní zdroj pro tento projekt:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                ExposedDropdownMenuBox(
                    expanded = resourceExpanded,
                    onExpandedChange = { resourceExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedResource ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(getResourceLabel(capability)) },
                        placeholder = { Text("Vyberte...") },
                        trailingIcon = {
                            if (isLoadingResources) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = resourceExpanded)
                            }
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = resourceExpanded && resources.isNotEmpty(),
                        onDismissRequest = { resourceExpanded = false }
                    ) {
                        resources.forEach { resource ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${resource.id} - ${resource.name}")
                                        resource.description?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onUpdateConfig(
                                        ProjectConnectionCapabilityDto(
                                            connectionId = connectionId,
                                            capability = capability,
                                            enabled = true,
                                            resourceIdentifier = resource.id,
                                            selectedResources = listOf(resource.id)
                                        )
                                    )
                                    resourceExpanded = false
                                }
                            )
                        }
                    }
                }

                if (resources.isEmpty() && !isLoadingResources) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Žádné dostupné zdroje.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun getCapabilityLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Bug Tracker"
        ConnectionCapability.WIKI -> "Wiki"
        ConnectionCapability.REPOSITORY -> "Repository"
        ConnectionCapability.EMAIL_READ -> "Email (Read)"
        ConnectionCapability.EMAIL_SEND -> "Email (Send)"
        ConnectionCapability.GIT -> "Git"
    }
}

private fun getResourceLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Projekt"
        ConnectionCapability.WIKI -> "Prostor"
        ConnectionCapability.REPOSITORY -> "Repozitář"
        ConnectionCapability.EMAIL_READ -> "Složka"
        ConnectionCapability.EMAIL_SEND -> "Odesílatel"
        ConnectionCapability.GIT -> "Repozitář"
    }
}
