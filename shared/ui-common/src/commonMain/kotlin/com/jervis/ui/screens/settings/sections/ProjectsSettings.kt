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
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
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
                                    if (project.resources.isNotEmpty()) {
                                        val summary = project.resources.groupBy { it.capability }
                                            .entries.joinToString(", ") { (cap, res) ->
                                                "${res.size}x ${getCapabilityLabel(cap)}"
                                            }
                                        Text(
                                            summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
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

    // Multi-resource model
    var resources by remember { mutableStateOf(project.resources.toMutableList()) }
    var resourceLinks by remember { mutableStateOf(project.resourceLinks.toMutableList()) }

    // Available resources from providers
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Add resource dialog
    var showAddResourceDialog by remember { mutableStateOf(false) }
    var addResourceCapabilityFilter by remember { mutableStateOf<ConnectionCapability?>(null) }

    // Link dialog
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkSourceResource by remember { mutableStateOf<ProjectResourceDto?>(null) }

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
                val res = repository.connections.listAvailableResources(connectionId, capability)
                availableResources = availableResources + (key to res)
            } catch (e: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    // Eager-load all available resources (parallel)
    LaunchedEffect(clientConnections) {
        clientConnections.forEach { conn ->
            conn.capabilities.forEach { cap ->
                loadResourcesForCapability(conn.id, cap)
            }
        }
    }

    fun addResource(connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) {
        // Avoid duplicates
        if (resources.any { it.connectionId == connectionId && it.capability == capability && it.resourceIdentifier == resourceId }) return
        resources = (resources + ProjectResourceDto(
            connectionId = connectionId,
            capability = capability,
            resourceIdentifier = resourceId,
            displayName = displayName,
        )).toMutableList()
    }

    fun removeResource(res: ProjectResourceDto) {
        resources = resources.filter { it !== res }.toMutableList()
        // Remove any links involving this resource
        resourceLinks = resourceLinks.filter { it.sourceId != res.id && it.targetId != res.id }.toMutableList()
    }

    fun addLink(sourceId: String, targetId: String) {
        if (resourceLinks.any { it.sourceId == sourceId && it.targetId == targetId }) return
        resourceLinks = (resourceLinks + ResourceLinkDto(sourceId, targetId)).toMutableList()
    }

    fun removeLink(link: ResourceLinkDto) {
        resourceLinks = resourceLinks.filter { it !== link }.toMutableList()
    }

    fun getLinksForResource(resourceId: String): List<ResourceLinkDto> =
        resourceLinks.filter { it.sourceId == resourceId || it.targetId == resourceId }

    fun findResourceById(id: String): ProjectResourceDto? = resources.find { it.id == id }

    fun getConnectionName(connectionId: String): String =
        clientConnections.find { it.id == connectionId }?.name ?: connectionId

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

            // Resources section
            JSection(title = "Zdroje projektu") {
                Text(
                    "Přidejte repozitáře, issue trackery, wiki a další zdroje z připojení klienta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                if (resources.isEmpty()) {
                    Text(
                        "Žádné zdroje. Klikněte na tlačítko pro přidání.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Group resources by capability, sorted alphabetically within each group
                    val grouped = resources.groupBy { it.capability }
                    grouped.forEach { (capability, unsorted) ->
                        val capResources = unsorted.sortedBy { (it.displayName.ifEmpty { it.resourceIdentifier }).lowercase() }
                        Text(
                            getCapabilityLabel(capability),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))

                        capResources.forEach { res ->
                            val links = getLinksForResource(res.id)
                            ProjectResourceItem(
                                resource = res,
                                connectionName = getConnectionName(res.connectionId),
                                links = links,
                                allResources = resources,
                                onRemove = { removeResource(res) },
                                onAddLink = {
                                    linkSourceResource = res
                                    showLinkDialog = true
                                },
                                onRemoveLink = { link -> removeLink(link) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        addResourceCapabilityFilter = null
                        showAddResourceDialog = true
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat zdroj")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Resource links section
            JSection(title = "Propojení zdrojů") {
                Text(
                    "Propojte repozitáře s issue trackery a wiki. Nepropojené zdroje jsou projekt-level.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                if (resourceLinks.isEmpty()) {
                    Text(
                        "Žádná propojení. Přidejte je tlačítkem u zdroje.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    resourceLinks.forEach { link ->
                        val source = findResourceById(link.sourceId)
                        val target = findResourceById(link.targetId)
                        if (source != null && target != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${source.displayName.ifEmpty { source.resourceIdentifier }} ↔ ${target.displayName.ifEmpty { target.resourceIdentifier }}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${getCapabilityLabel(source.capability)} ↔ ${getCapabilityLabel(target.capability)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                    IconButton(
                                        onClick = { removeLink(link) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Text("✕", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
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
                            resources = resources,
                            resourceLinks = resourceLinks,
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

    // Add Resource Dialog - trigger loading for any missing resources
    if (showAddResourceDialog) {
        LaunchedEffect(Unit) {
            clientConnections.forEach { conn ->
                conn.capabilities.forEach { cap ->
                    loadResourcesForCapability(conn.id, cap)
                }
            }
        }
        AddResourceDialog(
            clientConnections = clientConnections,
            availableResources = availableResources,
            loadingResources = loadingResources,
            existingResources = resources,
            capabilityFilter = addResourceCapabilityFilter,
            onAdd = { connectionId, capability, resourceId, displayName ->
                addResource(connectionId, capability, resourceId, displayName)
            },
            onDismiss = { showAddResourceDialog = false },
        )
    }

    // Link Resource Dialog
    if (showLinkDialog && linkSourceResource != null) {
        LinkResourceDialog(
            sourceResource = linkSourceResource!!,
            allResources = resources,
            existingLinks = resourceLinks,
            onLink = { targetId ->
                addLink(linkSourceResource!!.id, targetId)
            },
            onDismiss = {
                showLinkDialog = false
                linkSourceResource = null
            },
        )
    }
}

@Composable
private fun ProjectResourceItem(
    resource: ProjectResourceDto,
    connectionName: String,
    links: List<ResourceLinkDto>,
    allResources: List<ProjectResourceDto>,
    onRemove: () -> Unit,
    onAddLink: () -> Unit,
    onRemoveLink: (ResourceLinkDto) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    resource.displayName.ifEmpty { resource.resourceIdentifier },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${resource.resourceIdentifier} · $connectionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (links.isNotEmpty()) {
                    val linkedNames = links.mapNotNull { link ->
                        val otherId = if (link.sourceId == resource.id) link.targetId else link.sourceId
                        allResources.find { it.id == otherId }
                            ?.let { it.displayName.ifEmpty { it.resourceIdentifier } }
                    }
                    Text(
                        "↔ ${linkedNames.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Only show link button for resources that can be linked to others
            if (resource.id.isNotEmpty()) {
                IconButton(onClick = onAddLink, modifier = Modifier.size(28.dp)) {
                    Text("+↔", style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Text("✕", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class ResourceSelection(
    val connectionId: String,
    val capability: ConnectionCapability,
    val resourceId: String,
    val displayName: String,
)

@Composable
private fun AddResourceDialog(
    clientConnections: List<ConnectionResponseDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    existingResources: List<ProjectResourceDto>,
    capabilityFilter: ConnectionCapability?,
    onAdd: (connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Set<ResourceSelection>>(emptySet()) }
    var filterText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat zdroje") },
        text = {
            Column {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text("Filtrovat...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    clientConnections.forEach { connection ->
                        val caps = if (capabilityFilter != null) {
                            connection.capabilities.filter { it == capabilityFilter }
                        } else {
                            connection.capabilities
                        }

                        caps.forEach { capability ->
                            val key = Pair(connection.id, capability)
                            val allResources = availableResources[key] ?: emptyList()
                            val isLoading = key in loadingResources
                            val notYetLoaded = key !in availableResources

                            val filteredResources = allResources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    filterText.isBlank() ||
                                        res.name.contains(filterText, ignoreCase = true) ||
                                        res.id.contains(filterText, ignoreCase = true) ||
                                        (res.description?.contains(filterText, ignoreCase = true) == true)
                                }

                            if (isLoading || notYetLoaded) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${connection.name} · ${getCapabilityLabel(capability)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            } else if (filteredResources.isNotEmpty()) {
                                item {
                                    Text(
                                        "${connection.name} · ${getCapabilityLabel(capability)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(filteredResources) { resource ->
                                    val alreadyAdded = existingResources.any {
                                        it.connectionId == connection.id &&
                                            it.capability == capability &&
                                            it.resourceIdentifier == resource.id
                                    }
                                    val sel = ResourceSelection(connection.id, capability, resource.id, resource.name)
                                    val isSelected = sel in selected

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !alreadyAdded) {
                                                selected = if (isSelected) selected - sel else selected + sel
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = alreadyAdded || isSelected,
                                            onCheckedChange = if (alreadyAdded) null else { _ ->
                                                selected = if (isSelected) selected - sel else selected + sel
                                            },
                                            enabled = !alreadyAdded,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                resource.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (alreadyAdded) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            Text(
                                                resource.id + (resource.description?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = if (alreadyAdded) 0.3f else 0.7f
                                                ),
                                                maxLines = 1,
                                            )
                                        }
                                        if (alreadyAdded) {
                                            Text(
                                                "Přidáno",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Zavřít")
                }
                if (selected.isNotEmpty()) {
                    Button(onClick = {
                        selected.forEach { sel ->
                            onAdd(sel.connectionId, sel.capability, sel.resourceId, sel.displayName)
                        }
                        onDismiss()
                    }) {
                        Text("Přidat vybrané (${selected.size})")
                    }
                }
            }
        },
    )
}

@Composable
private fun LinkResourceDialog(
    sourceResource: ProjectResourceDto,
    allResources: List<ProjectResourceDto>,
    existingLinks: List<ResourceLinkDto>,
    onLink: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Show resources that can be linked (different capability, not already linked)
    val linkableResources = allResources.filter { target ->
        target.id != sourceResource.id &&
            target.id.isNotEmpty() &&
            target.capability != sourceResource.capability &&
            !existingLinks.any {
                (it.sourceId == sourceResource.id && it.targetId == target.id) ||
                    (it.sourceId == target.id && it.targetId == sourceResource.id)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Propojit: ${sourceResource.displayName.ifEmpty { sourceResource.resourceIdentifier }}")
        },
        text = {
            if (linkableResources.isEmpty()) {
                Text(
                    "Žádné dostupné zdroje pro propojení. Přidejte zdroje jiného typu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    val grouped = linkableResources.groupBy { it.capability }
                    grouped.forEach { (capability, resources) ->
                        item {
                            Text(
                                getCapabilityLabel(capability),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(resources) { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLink(target.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        target.displayName.ifEmpty { target.resourceIdentifier },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        target.resourceIdentifier,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        },
    )
}

private fun getCapabilityLabel(capability: ConnectionCapability): String {
    return when (capability) {
        ConnectionCapability.BUGTRACKER -> "Bug Tracker"
        ConnectionCapability.WIKI -> "Wiki"
        ConnectionCapability.REPOSITORY -> "Repository"
        ConnectionCapability.EMAIL_READ -> "Email (Read)"
        ConnectionCapability.EMAIL_SEND -> "Email (Send)"
    }
}
