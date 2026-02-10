package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.environment.*
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun EnvironmentsSettings(repository: JervisRepository) {
    var environments by remember { mutableStateOf<List<EnvironmentDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedEnv by remember { mutableStateOf<EnvironmentDto?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                environments = repository.environments.listEnvironments("")
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    JListDetailLayout(
        items = environments,
        selectedItem = selectedEnv,
        isLoading = isLoading,
        onItemSelected = { selectedEnv = it },
        emptyMessage = "Žádná prostředí",
        emptyIcon = "🌐",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = { showNewDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nové prostředí")
                }
            }
        },
        listItem = { env ->
            JCard(
                onClick = { selectedEnv = env },
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(env.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            env.namespace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val infraCount = env.components.count { it.type != ComponentTypeEnum.PROJECT }
                            val projectCount = env.components.count { it.type == ComponentTypeEnum.PROJECT }
                            Text(
                                "${infraCount} infra · ${projectCount} projekt",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            EnvironmentStateBadge(env.state)
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        detailContent = { env ->
            EnvironmentEditForm(
                environment = env,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.environments.updateEnvironment(updated.id, updated)
                            selectedEnv = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onProvision = {
                    scope.launch {
                        try {
                            repository.environments.provisionEnvironment(env.id)
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onDeprovision = {
                    scope.launch {
                        try {
                            repository.environments.deprovisionEnvironment(env.id)
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        try {
                            repository.environments.deleteEnvironment(env.id)
                            selectedEnv = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onCancel = { selectedEnv = null },
            )
        },
    )

    if (showNewDialog) {
        NewEnvironmentDialog(
            repository = repository,
            onCreated = {
                showNewDialog = false
                loadData()
            },
            onDismiss = { showNewDialog = false },
        )
    }
}

@Composable
private fun EnvironmentStateBadge(state: EnvironmentStateEnum) {
    val status = when (state) {
        EnvironmentStateEnum.PENDING -> "Čeká"
        EnvironmentStateEnum.CREATING -> "Vytváří se"
        EnvironmentStateEnum.RUNNING -> "Běží"
        EnvironmentStateEnum.STOPPING -> "Zastavuje se"
        EnvironmentStateEnum.STOPPED -> "Zastaveno"
        EnvironmentStateEnum.ERROR -> "Chyba"
    }
    JStatusBadge(status = status)
}

@Composable
private fun EnvironmentEditForm(
    environment: EnvironmentDto,
    repository: JervisRepository,
    onSave: (EnvironmentDto) -> Unit,
    onProvision: () -> Unit,
    onDeprovision: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(environment.name) }
    var description by remember { mutableStateOf(environment.description ?: "") }
    var namespace by remember { mutableStateOf(environment.namespace) }
    var agentInstructions by remember { mutableStateOf(environment.agentInstructions ?: "") }
    var components by remember { mutableStateOf(environment.components.toMutableList()) }
    var showAddComponentDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    JDetailScreen(
        title = environment.name,
        onBack = onCancel,
        onSave = {
            onSave(
                environment.copy(
                    name = name,
                    description = description.ifBlank { null },
                    namespace = namespace,
                    agentInstructions = agentInstructions.ifBlank { null },
                    components = components,
                ),
            )
        },
        saveEnabled = name.isNotBlank() && namespace.isNotBlank(),
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
                        label = "Název prostředí",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = namespace,
                        onValueChange = { namespace = it },
                        label = "K8s Namespace",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Popis",
                        singleLine = false,
                    )
                }

                JSection(title = "Komponenty") {
                    Text(
                        "Infrastrukturní komponenty (DB, cache) a projektové reference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (components.isEmpty()) {
                        Text(
                            "Žádné komponenty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        components.forEach { component ->
                            JCard {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            component.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        val typeLabel = componentTypeLabel(component.type)
                                        val imageInfo = component.image?.let { " · $it" } ?: ""
                                        Text(
                                            "$typeLabel$imageInfo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (component.ports.isNotEmpty()) {
                                            Text(
                                                component.ports.joinToString(", ") { "${it.containerPort}" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    JRemoveIconButton(
                                        onConfirmed = {
                                            components = components.filter { it.id != component.id }.toMutableList()
                                        },
                                        title = "Odebrat komponentu?",
                                        message = "Komponenta \"${component.name}\" bude odebrána z prostředí.",
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    JPrimaryButton(onClick = { showAddComponentDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat komponentu")
                    }
                }

                JSection(title = "Instrukce pro agenta") {
                    JTextField(
                        value = agentInstructions,
                        onValueChange = { agentInstructions = it },
                        label = "Instrukce (volitelné)",
                        singleLine = false,
                    )
                    Text(
                        "Tyto instrukce budou předány coding agentovi jako kontext prostředí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Provisioning actions
                JSection(title = "Správa prostředí") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (environment.state == EnvironmentStateEnum.PENDING ||
                            environment.state == EnvironmentStateEnum.STOPPED ||
                            environment.state == EnvironmentStateEnum.ERROR
                        ) {
                            JPrimaryButton(onClick = onProvision) {
                                Text("Provisionovat")
                            }
                        }
                        if (environment.state == EnvironmentStateEnum.RUNNING) {
                            JSecondaryButton(onClick = onDeprovision) {
                                Text("Zastavit")
                            }
                        }
                    }
                }

                JSection(title = "Nebezpečná zóna") {
                    JDestructiveButton(onClick = { showDeleteConfirm = true }) {
                        Text("Smazat prostředí")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    ConfirmDialog(
        visible = showDeleteConfirm,
        title = "Smazat prostředí?",
        message = "Tato akce je nevratná. Pokud je prostředí provisionované, bude nejdříve zastaveno.",
        confirmText = "Smazat",
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )

    if (showAddComponentDialog) {
        AddComponentDialog(
            existingComponents = components,
            onAdd = { newComponent ->
                components = (components + newComponent).toMutableList()
            },
            onDismiss = { showAddComponentDialog = false },
        )
    }
}

@Composable
private fun AddComponentDialog(
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

private fun componentTypeLabel(type: ComponentTypeEnum): String = when (type) {
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

@Composable
private fun NewEnvironmentDialog(
    repository: JervisRepository,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var namespace by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
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
        confirmEnabled = name.isNotBlank() && namespace.isNotBlank() && selectedClientId != null && !isSaving,
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
    }
}
