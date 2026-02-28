package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.filterVisible
import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.ComponentVersionDto
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentTierEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import kotlinx.coroutines.launch

@Composable
fun AddComponentDialog(
    existingComponents: List<EnvironmentComponentDto>,
    templates: List<ComponentTemplateDto> = emptyList(),
    onAdd: (EnvironmentComponentDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ComponentTypeEnum.POSTGRESQL) }
    var customImage by remember { mutableStateOf("") }
    var useCustomImage by remember { mutableStateOf(false) }

    // Find template for current type
    val currentTemplate = templates.find { it.type == selectedType }
    val versions = currentTemplate?.versions ?: emptyList()
    var selectedVersion by remember { mutableStateOf<ComponentVersionDto?>(null) }

    // When type changes, reset version to first available
    LaunchedEffect(selectedType) {
        val template = templates.find { it.type == selectedType }
        selectedVersion = template?.versions?.firstOrNull()
        useCustomImage = false
        customImage = ""
    }

    val resolvedImage = when {
        useCustomImage -> customImage.ifBlank { null }
        selectedVersion != null -> selectedVersion!!.image
        else -> null
    }

    val defaultPorts = currentTemplate?.defaultPorts ?: emptyList()
    val defaultEnvVars = currentTemplate?.defaultEnvVars ?: emptyMap()
    val volumeMountPath = currentTemplate?.defaultVolumeMountPath

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
                    image = resolvedImage,
                    ports = defaultPorts,
                    envVars = defaultEnvVars,
                    volumeMountPath = volumeMountPath,
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
            // Version picker (if template has versions)
            if (versions.isNotEmpty() && !useCustomImage) {
                Spacer(Modifier.height(12.dp))
                JDropdown(
                    items = versions,
                    selectedItem = selectedVersion,
                    onItemSelected = { selectedVersion = it },
                    label = "Verze",
                    itemLabel = { it.label },
                )
                // Show resolved image in monospace
                selectedVersion?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.image,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Custom image toggle / field
            Spacer(Modifier.height(12.dp))
            if (versions.isNotEmpty()) {
                com.jervis.ui.design.JTextButton(
                    onClick = { useCustomImage = !useCustomImage },
                ) {
                    Text(if (useCustomImage) "Zpět na výběr verze" else "Vlastní image")
                }
            }
            if (useCustomImage || versions.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                JTextField(
                    value = customImage,
                    onValueChange = { customImage = it },
                    label = "Docker image",
                    singleLine = true,
                )
            }

            // Template defaults preview (read-only)
            if (defaultPorts.isNotEmpty() || defaultEnvVars.isNotEmpty() || volumeMountPath != null) {
                Spacer(Modifier.height(8.dp))
                val parts = buildList {
                    if (defaultPorts.isNotEmpty()) add("Porty: ${defaultPorts.joinToString(", ") { "${it.containerPort}" }}")
                    if (defaultEnvVars.isNotEmpty()) add("${defaultEnvVars.size} ENV")
                    volumeMountPath?.let { add("Volume: $it") }
                }
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

fun environmentTierLabel(tier: EnvironmentTierEnum): String = when (tier) {
    EnvironmentTierEnum.DEV -> "Development"
    EnvironmentTierEnum.STAGING -> "Staging"
    EnvironmentTierEnum.PROD -> "Production"
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
    var selectedTier by remember { mutableStateOf(EnvironmentTierEnum.DEV) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var selectedScope by remember { mutableStateOf(EnvironmentScope.CLIENT) }
    var groups by remember { mutableStateOf<List<com.jervis.dto.ProjectGroupDto>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
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
                val allProjects = repository.projects.listProjectsForClient(clientId).filterVisible()
                projects = allProjects
            } catch (_: Exception) {
            }
        }
    }

    val nsRegex = remember { Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$") }
    val reservedNamespaces = remember { setOf("default", "kube-system", "kube-public", "kube-node-lease", "jervis", "monitoring", "logging") }
    val reservedPrefixes = remember { listOf("kube-", "openshift-", "istio-") }

    val namespaceError = when {
        namespace.isBlank() -> null
        namespace.length > 63 -> "Max 63 znaků"
        !nsRegex.matches(namespace) -> "Jen malá písmena, čísla a pomlčky"
        namespace in reservedNamespaces -> "Rezervovaný namespace"
        reservedPrefixes.any { namespace.startsWith(it) } -> "Zakázaný prefix"
        else -> null
    }

    val isValid = name.isNotBlank() && namespace.isNotBlank() && namespaceError == null &&
        selectedClientId != null &&
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
                            tier = selectedTier,
                            namespace = namespace,
                        ),
                    )
                    onCreated()
                } catch (e: Exception) {
                    saveError = e.message
                    isSaving = false
                }
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = isValid && !isSaving,
        confirmText = "Vytvořit",
    ) {
        saveError?.let { errorMsg ->
            Text(
                errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        JTextField(
            value = name,
            onValueChange = {
                name = it
                saveError = null
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
            isError = namespaceError != null,
            errorMessage = namespaceError,
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = EnvironmentTierEnum.entries.toList(),
            selectedItem = selectedTier,
            onItemSelected = { selectedTier = it },
            label = "Typ prostředí",
            itemLabel = { environmentTierLabel(it) },
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
