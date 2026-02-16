package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import kotlinx.coroutines.launch

@Composable
fun AddComponentDialog(
    existingComponents: List<EnvironmentComponentDto>,
    onAdd: (EnvironmentComponentDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ComponentTypeEnum.POSTGRESQL) }
    var image by remember { mutableStateOf("") }

    JFormDialog(
        visible = true,
        title = "Přidat komponentu",
        onConfirm = {
            val id = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            onAdd(
                EnvironmentComponentDto(
                    id = id,
                    name = name,
                    type = selectedType,
                    image = image.ifBlank { null },
                ),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
        confirmText = "Přidat",
    ) {
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název",
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = ComponentTypeEnum.entries.toList(),
            selectedItem = selectedType,
            onItemSelected = { selectedType = it },
            label = "Typ",
            itemLabel = { componentTypeLabel(it) },
        )
        if (selectedType != ComponentTypeEnum.PROJECT) {
            Spacer(Modifier.height(12.dp))
            JTextField(
                value = image,
                onValueChange = { image = it },
                label = "Docker image (volitelné, výchozí bude použit)",
                singleLine = true,
            )
        }
    }
}

fun componentTypeLabel(type: ComponentTypeEnum): String = when (type) {
    ComponentTypeEnum.POSTGRESQL -> "PostgreSQL"
    ComponentTypeEnum.MONGODB -> "MongoDB"
    ComponentTypeEnum.REDIS -> "Redis"
    ComponentTypeEnum.RABBITMQ -> "RabbitMQ"
    ComponentTypeEnum.KAFKA -> "Kafka"
    ComponentTypeEnum.ELASTICSEARCH -> "Elasticsearch"
    ComponentTypeEnum.ORACLE -> "Oracle"
    ComponentTypeEnum.MYSQL -> "MySQL"
    ComponentTypeEnum.MINIO -> "MinIO"
    ComponentTypeEnum.CUSTOM_INFRA -> "Vlastní infra"
    ComponentTypeEnum.PROJECT -> "Projekt"
}

enum class EnvironmentScope {
    CLIENT, GROUP, PROJECT
}

@Composable
fun NewEnvironmentDialog(
    repository: JervisRepository,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var namespace by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var selectedScope by remember { mutableStateOf(EnvironmentScope.CLIENT) }
    var groups by remember { mutableStateOf<List<com.jervis.dto.ProjectGroupDto>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.getAllClients()
            if (clients.size == 1) {
                selectedClientId = clients.first().id
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(selectedClientId) {
        selectedClientId?.let { clientId ->
            try {
                val allGroups = repository.projectGroups.getAllGroups()
                groups = allGroups.filter { it.clientId == clientId }
                val allProjects = repository.projects.listProjectsForClient(clientId)
                projects = allProjects
            } catch (_: Exception) {
            }
        }
    }

    val isValid = name.isNotBlank() && namespace.isNotBlank() && selectedClientId != null &&
        when (selectedScope) {
            EnvironmentScope.CLIENT -> true
            EnvironmentScope.GROUP -> selectedGroupId != null
            EnvironmentScope.PROJECT -> selectedProjectId != null
        }

    JFormDialog(
        visible = true,
        title = "Nové prostředí",
        onConfirm = {
            val clientId = selectedClientId ?: return@JFormDialog
            isSaving = true
            scope.launch {
                try {
                    repository.environments.saveEnvironment(
                        EnvironmentDto(
                            clientId = clientId,
                            groupId = if (selectedScope == EnvironmentScope.GROUP) selectedGroupId else null,
                            projectId = if (selectedScope == EnvironmentScope.PROJECT) selectedProjectId else null,
                            name = name,
                            namespace = namespace,
                        ),
                    )
                    onCreated()
                } catch (_: Exception) {
                    isSaving = false
                }
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = isValid && !isSaving,
        confirmText = "Vytvořit",
    ) {
        JTextField(
            value = name,
            onValueChange = {
                name = it
                if (namespace.isBlank() || namespace == name.lowercase().replace(Regex("[^a-z0-9-]"), "-").dropLast(1)) {
                    namespace = it.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                }
            },
            label = "Název",
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        JTextField(
            value = namespace,
            onValueChange = { namespace = it },
            label = "K8s Namespace",
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = clients,
            selectedItem = clients.find { it.id == selectedClientId },
            onItemSelected = { selectedClientId = it.id },
            label = "Klient",
            itemLabel = { it.name },
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = EnvironmentScope.entries.toList(),
            selectedItem = selectedScope,
            onItemSelected = { selectedScope = it },
            label = "Rozsah",
            itemLabel = { when (it) {
                EnvironmentScope.CLIENT -> "Klient (všechny projekty)"
                EnvironmentScope.GROUP -> "Skupina (projekty ve skupině)"
                EnvironmentScope.PROJECT -> "Projekt (jen jeden projekt)"
            }},
        )
        if (selectedScope == EnvironmentScope.GROUP && groups.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            JDropdown(
                items = groups,
                selectedItem = groups.find { it.id == selectedGroupId },
                onItemSelected = { selectedGroupId = it.id },
                label = "Skupina",
                itemLabel = { it.name },
            )
        }
        if (selectedScope == EnvironmentScope.PROJECT && projects.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            JDropdown(
                items = projects,
                selectedItem = projects.find { it.id == selectedProjectId },
                onItemSelected = { selectedProjectId = it.id },
                label = "Projekt",
                itemLabel = { it.name },
            )
        }
    }
}
