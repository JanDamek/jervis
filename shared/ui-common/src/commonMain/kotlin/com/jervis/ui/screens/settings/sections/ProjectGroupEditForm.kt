package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.jervis.dto.ProjectGroupDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JRemoveIconButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
internal fun ProjectGroupEditForm(
    group: ProjectGroupDto,
    repository: JervisRepository,
    onSave: (ProjectGroupDto) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description ?: "") }

    // Resources model (same pattern as ProjectEditForm)
    var resources by remember { mutableStateOf(group.resources.toMutableList()) }
    var resourceLinks by remember { mutableStateOf(group.resourceLinks.toMutableList()) }

    // Client and connections
    var clientConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Projects in this group
    var projectsInGroup by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var allProjects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }

    var showAddResourceDialog by remember { mutableStateOf(false) }
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(group.clientId) {
        try {
            val client = repository.clients.getClientById(group.clientId) ?: return@LaunchedEffect
            val allConnections = repository.connections.getAllConnections()
            clientConnections = allConnections.filter { conn ->
                client.connectionIds.contains(conn.id)
            }
            // Load projects
            allProjects = repository.projects.listProjectsForClient(group.clientId)
            projectsInGroup = allProjects.filter { it.groupId == group.id }
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

    JDetailScreen(
        title = group.name,
        onBack = onCancel,
        onSave = {
            onSave(
                group.copy(
                    name = name,
                    description = description.ifBlank { null },
                    resources = resources,
                    resourceLinks = resourceLinks,
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
                        label = "Název skupiny",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Popis",
                        singleLine = false,
                    )
                }

                JSection(title = "Projekty ve skupině") {
                    Text(
                        "Projekty přiřazené do této skupiny sdílí KB data a group-level zdroje.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (projectsInGroup.isEmpty()) {
                        Text(
                            "Žádné projekty ve skupině.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        projectsInGroup.forEach { project ->
                            JCard {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            project.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        project.description?.let { desc ->
                                            if (desc.isNotBlank()) {
                                                Text(
                                                    desc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    JRemoveIconButton(
                                        onConfirmed = {
                                            scope.launch {
                                                try {
                                                    repository.projects.updateProject(
                                                        project.id,
                                                        project.copy(groupId = null),
                                                    )
                                                    projectsInGroup = projectsInGroup.filter { it.id != project.id }
                                                    allProjects = repository.projects.listProjectsForClient(group.clientId)
                                                } catch (_: Exception) {
                                                }
                                            }
                                        },
                                        title = "Odebrat projekt ze skupiny?",
                                        message = "Projekt \"${project.name}\" bude odebrán ze skupiny (nebude smazán).",
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    JPrimaryButton(onClick = { showAddProjectDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat projekt")
                    }
                }

                JSection(title = "Sdílené zdroje skupiny") {
                    Text(
                        "Zdroje na úrovni skupiny jsou sdílené všemi projekty ve skupině.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (resources.isEmpty()) {
                        Text(
                            "Žádné sdílené zdroje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        resources.forEach { res ->
                            JCard {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            res.displayName.ifEmpty { res.resourceIdentifier },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "${getCapabilityLabel(res.capability)} · ${res.resourceIdentifier}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    JRemoveIconButton(
                                        onConfirmed = { removeResource(res) },
                                        title = "Odebrat zdroj?",
                                        message = "Zdroj \"${res.displayName.ifEmpty { res.resourceIdentifier }}\" bude odebrán ze skupiny.",
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    JPrimaryButton(onClick = { showAddResourceDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat zdroj")
                    }
                }

                // Delete group
                JSection(title = "Nebezpečná zóna") {
                    JDestructiveButton(onClick = { showDeleteConfirm = true }) {
                        Text("Smazat skupinu")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    ConfirmDialog(
        visible = showDeleteConfirm,
        title = "Smazat skupinu?",
        message = "Projekty ve skupině nebudou smazány, pouze odřazeny ze skupiny.",
        confirmText = "Smazat",
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )

    if (showAddResourceDialog) {
        AddGroupResourceDialog(
            clientConnections = clientConnections,
            availableResources = availableResources,
            loadingResources = loadingResources,
            existingResources = resources,
            onAdd = { connectionId, capability, resourceId, displayName ->
                addResource(connectionId, capability, resourceId, displayName)
            },
            onDismiss = { showAddResourceDialog = false },
        )
    }

    if (showAddProjectDialog) {
        AddProjectToGroupDialog(
            allProjects = allProjects,
            projectsInGroup = projectsInGroup,
            groupId = group.id,
            repository = repository,
            onAdded = { addedProject ->
                projectsInGroup = (projectsInGroup + addedProject).sortedBy { it.name }
                scope.launch {
                    try {
                        allProjects = repository.projects.listProjectsForClient(group.clientId)
                    } catch (_: Exception) {
                    }
                }
            },
            onDismiss = { showAddProjectDialog = false },
        )
    }
}
