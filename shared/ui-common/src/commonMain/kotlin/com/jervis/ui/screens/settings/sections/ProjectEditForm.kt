package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGroupDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import kotlinx.coroutines.launch

@Composable
internal fun ProjectEditForm(
    project: ProjectDto,
    repository: JervisRepository,
    onSave: (ProjectDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description ?: "") }

    // Client, groups and connections
    var client by remember { mutableStateOf<ClientDto?>(null) }
    var clientGroups by remember { mutableStateOf<List<ProjectGroupDto>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf(project.groupId) }
    var clientConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }

    // Multi-resource model
    var resources by remember { mutableStateOf(project.resources.toMutableList()) }
    var resourceLinks by remember { mutableStateOf(project.resourceLinks.toMutableList()) }

    // Available resources from providers
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Dialogs
    var showAddResourceDialog by remember { mutableStateOf(false) }
    var addResourceCapabilityFilter by remember { mutableStateOf<ConnectionCapability?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkSourceResource by remember { mutableStateOf<ProjectResourceDto?>(null) }

    // Git commit configuration (can override client's config)
    var useCustomGitConfig by remember {
        mutableStateOf(
            project.gitCommitAuthorName != null || project.gitCommitMessageFormat != null,
        )
    }
    var gitCommitMessageFormat by remember { mutableStateOf(project.gitCommitMessageFormat ?: "") }
    var gitCommitMessagePattern by remember { mutableStateOf(project.gitCommitMessagePattern ?: "") }
    var gitCommitAuthorName by remember { mutableStateOf(project.gitCommitAuthorName ?: "") }
    var gitCommitAuthorEmail by remember { mutableStateOf(project.gitCommitAuthorEmail ?: "") }
    var gitCommitCommitterName by remember { mutableStateOf(project.gitCommitCommitterName ?: "") }
    var gitCommitCommitterEmail by remember { mutableStateOf(project.gitCommitCommitterEmail ?: "") }
    var gitCommitGpgSign by remember { mutableStateOf(project.gitCommitGpgSign ?: false) }
    var gitCommitGpgKeyId by remember { mutableStateOf(project.gitCommitGpgKeyId ?: "") }

    // Cloud model policy override
    var overrideCloudPolicy by remember {
        mutableStateOf(
            project.autoUseAnthropic != null || project.autoUseOpenai != null || project.autoUseGemini != null,
        )
    }
    var autoUseAnthropic by remember { mutableStateOf(project.autoUseAnthropic ?: false) }
    var autoUseOpenai by remember { mutableStateOf(project.autoUseOpenai ?: false) }
    var autoUseGemini by remember { mutableStateOf(project.autoUseGemini ?: false) }

    val scope = rememberCoroutineScope()

    // Load client, groups and connections
    LaunchedEffect(project.clientId) {
        try {
            val cid = project.clientId
            if (cid != null) {
                client = repository.clients.getClientById(cid)
                val allConnections = repository.connections.getAllConnections()
                clientConnections = allConnections.filter { conn ->
                    client?.connectionIds?.contains(conn.id) == true
                }
                clientGroups = repository.projectGroups.listGroupsForClient(cid)
            }
        } catch (_: Exception) {
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
            } catch (_: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    LaunchedEffect(clientConnections) {
        clientConnections.forEach { conn ->
            conn.capabilities.forEach { cap ->
                loadResourcesForCapability(conn.id, cap)
            }
        }
    }

    fun addResource(connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) {
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

    JDetailScreen(
        title = project.name,
        onBack = onCancel,
        onSave = {
            onSave(
                project.copy(
                    name = name,
                    description = description.ifBlank { null },
                    groupId = selectedGroupId,
                    resources = resources,
                    resourceLinks = resourceLinks,
                    gitCommitMessageFormat = if (useCustomGitConfig) gitCommitMessageFormat.ifBlank { null } else null,
                    gitCommitMessagePattern = if (useCustomGitConfig) gitCommitMessagePattern.ifBlank { null } else null,
                    gitCommitAuthorName = if (useCustomGitConfig) gitCommitAuthorName.ifBlank { null } else null,
                    gitCommitAuthorEmail = if (useCustomGitConfig) gitCommitAuthorEmail.ifBlank { null } else null,
                    gitCommitCommitterName = if (useCustomGitConfig) gitCommitCommitterName.ifBlank { null } else null,
                    gitCommitCommitterEmail = if (useCustomGitConfig) gitCommitCommitterEmail.ifBlank { null } else null,
                    gitCommitGpgSign = if (useCustomGitConfig) gitCommitGpgSign else null,
                    gitCommitGpgKeyId = if (useCustomGitConfig) gitCommitGpgKeyId.ifBlank { null } else null,
                    autoUseAnthropic = if (overrideCloudPolicy) autoUseAnthropic else null,
                    autoUseOpenai = if (overrideCloudPolicy) autoUseOpenai else null,
                    autoUseGemini = if (overrideCloudPolicy) autoUseGemini else null,
                ),
            )
        },
        saveEnabled = name.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()

        SelectionContainer {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Základní informace") {
                    JTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Název projektu",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Popis",
                        singleLine = false,
                        minLines = 2,
                    )

                    // Group selector
                    if (clientGroups.isNotEmpty()) {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JDropdown(
                            items = listOf<ProjectGroupDto?>(null) + clientGroups,
                            selectedItem = clientGroups.find { it.id == selectedGroupId },
                            onItemSelected = { selectedGroupId = it?.id },
                            label = "Skupina projektů",
                            itemLabel = { it?.name ?: "(Žádná skupina)" },
                        )
                    }
                }

                // Resources section
                JSection(title = "Zdroje projektu") {
                    Text(
                        "Přidejte repozitáře, issue trackery, wiki a další zdroje z připojení klienta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (resources.isEmpty()) {
                        Text(
                            "Žádné zdroje. Klikněte na tlačítko pro přidání.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val grouped = resources.groupBy { it.capability }
                        grouped.forEach { (capability, unsorted) ->
                            val capResources = unsorted.sortedBy { (it.displayName.ifEmpty { it.resourceIdentifier }).lowercase() }
                            Text(
                                getCapabilityLabel(capability),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
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

                    JPrimaryButton(onClick = {
                        addResourceCapabilityFilter = null
                        showAddResourceDialog = true
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat zdroj")
                    }
                }

                // Resource links section
                JSection(title = "Propojení zdrojů") {
                    Text(
                        "Propojte repozitáře s issue trackery a wiki. Nepropojené zdroje jsou projekt-level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (resourceLinks.isEmpty()) {
                        Text(
                            "Žádná propojení. Přidejte je tlačítkem u zdroje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        resourceLinks.forEach { link ->
                            val source = findResourceById(link.sourceId)
                            val target = findResourceById(link.targetId)
                            if (source != null && target != null) {
                                JCard {
                                    Row(
                                        modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
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
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        JRemoveIconButton(
                                            onConfirmed = { removeLink(link) },
                                            title = "Odebrat propojení?",
                                            message = "Propojení bude odebráno z projektu.",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                JSection(title = "Přepsání Git Commit Konfigurace") {
                    Text(
                        "Standardně se používá konfigurace z klienta. Zde můžete přepsat pro tento projekt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    JCheckboxRow(
                        label = "Přepsat konfiguraci klienta",
                        checked = useCustomGitConfig,
                        onCheckedChange = { useCustomGitConfig = it },
                    )

                    if (useCustomGitConfig) {
                        Spacer(Modifier.height(12.dp))

                        GitCommitConfigFields(
                            messageFormat = gitCommitMessageFormat,
                            onMessageFormatChange = { gitCommitMessageFormat = it },
                            messagePattern = gitCommitMessagePattern,
                            onMessagePatternChange = { gitCommitMessagePattern = it },
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
                }

                JSection(title = "Cloud modely – přepsání") {
                    Text(
                        "Standardně se používá konfigurace z klienta. Zde můžete přepsat pro tento projekt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    JCheckboxRow(
                        label = "Přepsat konfiguraci klienta",
                        checked = overrideCloudPolicy,
                        onCheckedChange = {
                            overrideCloudPolicy = it
                            if (!it) {
                                autoUseAnthropic = false
                                autoUseOpenai = false
                                autoUseGemini = false
                            }
                        },
                    )

                    if (overrideCloudPolicy) {
                        Spacer(Modifier.height(12.dp))
                        JCheckboxRow(
                            label = "Anthropic (Claude) – reasoning, analýza, architektura",
                            checked = autoUseAnthropic,
                            onCheckedChange = { autoUseAnthropic = it },
                        )
                        JCheckboxRow(
                            label = "OpenAI (GPT-4o) – editace kódu, strukturovaný výstup",
                            checked = autoUseOpenai,
                            onCheckedChange = { autoUseOpenai = it },
                        )
                        JCheckboxRow(
                            label = "Google Gemini – pouze pro extrémní kontext (>49k tokenů)",
                            checked = autoUseGemini,
                            onCheckedChange = { autoUseGemini = it },
                        )
                    }
                }

                // Bottom spacing
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Add Resource Dialog
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
    JCard {
        Row(
            modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (resource.id.isNotEmpty()) {
                JIconButton(
                    onClick = onAddLink,
                    icon = Icons.Default.Link,
                    contentDescription = "Propojit",
                )
            }
            JRemoveIconButton(
                onConfirmed = onRemove,
                title = "Odebrat zdroj?",
                message = "Zdroj \"${resource.displayName.ifEmpty { resource.resourceIdentifier }}\" bude odebrán z projektu.",
            )
        }
    }
}
