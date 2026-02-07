package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedEnv = env },
                border = CardDefaults.outlinedCardBorder(),
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
    val (color, label) = when (state) {
        EnvironmentStateEnum.PENDING -> MaterialTheme.colorScheme.outline to "Čeká"
        EnvironmentStateEnum.CREATING -> MaterialTheme.colorScheme.tertiary to "Vytváří se"
        EnvironmentStateEnum.RUNNING -> MaterialTheme.colorScheme.primary to "Běží"
        EnvironmentStateEnum.STOPPING -> MaterialTheme.colorScheme.tertiary to "Zastavuje se"
        EnvironmentStateEnum.STOPPED -> MaterialTheme.colorScheme.outline to "Zastaveno"
        EnvironmentStateEnum.ERROR -> MaterialTheme.colorScheme.error to "Chyba"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JSection(title = "Základní informace") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název prostředí") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(JervisSpacing.itemGap))
                OutlinedTextField(
                    value = namespace,
                    onValueChange = { namespace = it },
                    label = { Text("K8s Namespace") },
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
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
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
                                IconButton(
                                    onClick = {
                                        components = components.filter { it.id != component.id }.toMutableList()
                                    },
                                    modifier = Modifier.size(JervisSpacing.touchTarget),
                                ) {
                                    Text("✕", style = MaterialTheme.typography.labelSmall)
                                }
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
                OutlinedTextField(
                    value = agentInstructions,
                    onValueChange = { agentInstructions = it },
                    label = { Text("Instrukce (volitelné)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
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
                        OutlinedButton(
                            onClick = onDeprovision,
                            modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                        ) {
                            Text("Zastavit")
                        }
                    }
                }
            }

            JSection(title = "Nebezpečná zóna") {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                ) {
                    Text("Smazat prostředí")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Smazat prostředí?") },
            text = { Text("Tato akce je nevratná. Pokud je prostředí provisionované, bude nejdříve zastaveno.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Smazat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Zrušit")
                }
            },
        )
    }

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
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat komponentu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = componentTypeLabel(selectedType),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Typ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        ComponentTypeEnum.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(componentTypeLabel(type)) },
                                onClick = {
                                    selectedType = type
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                if (selectedType != ComponentTypeEnum.PROJECT) {
                    OutlinedTextField(
                        value = image,
                        onValueChange = { image = it },
                        label = { Text("Docker image (volitelné, výchozí bude použit)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
                Button(
                    onClick = {
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
                    enabled = name.isNotBlank(),
                ) {
                    Text("Přidat")
                }
            }
        },
    )
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
    var expanded by remember { mutableStateOf(false) }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nové prostředí") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (namespace.isBlank() || namespace == name.lowercase().replace(Regex("[^a-z0-9-]"), "-").dropLast(1)) {
                            namespace = it.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                        }
                    },
                    label = { Text("Název") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = namespace,
                    onValueChange = { namespace = it },
                    label = { Text("K8s Namespace") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = clients.find { it.id == selectedClientId }?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Klient") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
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
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
                Button(
                    onClick = {
                        val clientId = selectedClientId ?: return@Button
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
                    enabled = name.isNotBlank() && namespace.isNotBlank() && selectedClientId != null && !isSaving,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Vytvořit")
                    }
                }
            }
        },
    )
}
