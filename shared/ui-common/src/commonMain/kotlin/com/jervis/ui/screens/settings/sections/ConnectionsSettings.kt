package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.connection.AuthOption
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.dto.connection.FormFieldType
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch
import com.jervis.ui.util.openUrlInBrowser

@Composable
fun ConnectionsSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var descriptors by remember { mutableStateOf(ProviderDescriptor.defaultsByProvider) }
    var isLoading by remember { mutableStateOf(false) }

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }
    var showDeleteDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }

    suspend fun loadConnections() {
        try {
            connections = repository.connections.getAllConnections()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání připojení: ${e.message}")
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            connections = repository.connections.getAllConnections()
            clients = repository.clients.getAllClients()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        }
        try {
            val serverDescriptors = repository.connections.getProviderDescriptors().associateBy { it.provider }
            if (serverDescriptors.isNotEmpty()) {
                descriptors = ProviderDescriptor.defaultsByProvider + serverDescriptors
            }
        } catch (_: Exception) {
            // Descriptors are optional - UI uses defaults from ProviderDescriptor.defaultsByProvider
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            JActionBar {
                RefreshIconButton(onClick = {
                    scope.launch { loadConnections() }
                })
                JPrimaryButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat připojení")
                }
            }

            Spacer(Modifier.height(JervisSpacing.itemGap))

            if (connections.isEmpty() && isLoading) {
                JCenteredLoading()
            } else if (connections.isEmpty() && !isLoading) {
                JEmptyState(message = "Žádná připojení nenalezena", icon = "🔌")
            } else {
                SelectionContainer {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(connections) { connection ->
                            ConnectionItemCard(
                                connection = connection,
                                clients = clients,
                                onTest = {
                                    scope.launch {
                                        try {
                                            val result = repository.connections.testConnection(connection.id)
                                            snackbarHostState.showSnackbar(
                                                if (result.success) "Test OK" else "Test selhal: ${result.message}",
                                            )
                                            loadConnections()
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba testu: ${e.message}")
                                            loadConnections()
                                        }
                                    }
                                },
                                onReauthorize = {
                                    scope.launch {
                                        try {
                                            val authUrl = repository.connections.initiateOAuth2(connection.id)
                                            openUrlInBrowser(authUrl)
                                            snackbarHostState.showSnackbar("OAuth2 re-autorizace spuštěna. Dokončete ji v prohlížeči.")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba re-autorizace: ${e.message}")
                                        }
                                    }
                                },
                                onEdit = { showEditDialog = connection },
                                onDelete = { showDeleteDialog = connection },
                            )
                        }
                    }
                }
            }
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }

    if (showCreateDialog) {
        ConnectionCreateDialog(
            descriptors = descriptors,
            onDismiss = { showCreateDialog = false },
            onCreate = { request ->
                scope.launch {
                    try {
                        val created = repository.connections.createConnection(request)
                        if (request.authType == AuthTypeEnum.OAUTH2) {
                            val authUrl = repository.connections.initiateOAuth2(created.id)
                            openUrlInBrowser(authUrl)
                            snackbarHostState.showSnackbar("OAuth2 autorizace byla spuštěna. Dokončete ji v prohlížeči.")
                        } else {
                            snackbarHostState.showSnackbar("Připojení vytvořeno")
                        }
                        loadConnections()
                        showCreateDialog = false
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
        )
    }

    showEditDialog?.let { connection ->
        ConnectionEditDialog(
            connection = connection,
            descriptors = descriptors,
            onDismiss = { showEditDialog = null },
            onSave = { id, request ->
                scope.launch {
                    try {
                        repository.connections.updateConnection(id, request)
                        snackbarHostState.showSnackbar("Připojení aktualizováno")
                        loadConnections()
                        showEditDialog = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
        )
    }

    showDeleteDialog?.let { connection ->
        ConfirmDialog(
            visible = true,
            title = "Smazat připojení",
            message = "Opravdu chcete smazat připojení '${connection.name}'?",
            onConfirm = {
                scope.launch {
                    try {
                        repository.connections.deleteConnection(connection.id)
                        snackbarHostState.showSnackbar("Připojení smazáno")
                        loadConnections()
                        showDeleteDialog = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
            onDismiss = { showDeleteDialog = null },
        )
    }
}

@Composable
private fun ConnectionItemCard(
    connection: ConnectionResponseDto,
    clients: List<ClientDto>,
    onTest: () -> Unit,
    onReauthorize: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val assignedClient = clients.firstOrNull { it.connectionIds.contains(connection.id) }

    JCard {
        // Header: name + status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(connection.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            SuggestionChip(
                onClick = {},
                label = { Text(connection.provider.name, style = MaterialTheme.typography.labelSmall) },
            )
            Spacer(Modifier.width(8.dp))
            JStatusBadge(status = connection.state.name)
        }

        // URL and client info
        Text(
            text = connection.displayUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (assignedClient != null) {
            Text(
                text = "Používá: ${assignedClient.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Capabilities
        if (connection.capabilities.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Funkce: ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                connection.capabilities.forEach { capability ->
                    CapabilityChip(capability)
                }
            }
        }

        // Action buttons - wrapped in a flow-like row for mobile
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JPrimaryButton(onClick = onTest) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Test")
                Spacer(Modifier.width(4.dp))
                Text("Test")
            }
            if (connection.authType == AuthTypeEnum.OAUTH2) {
                JPrimaryButton(onClick = onReauthorize) {
                    Text("Re-auth")
                }
            }
            JIconButton(
                onClick = onEdit,
                icon = Icons.Default.Edit,
                contentDescription = "Upravit",
            )
            JDestructiveButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Smazat")
            }
        }
    }
}

@Composable
private fun CapabilityChip(capability: ConnectionCapability) {
    val (label, color) = when (capability) {
        ConnectionCapability.BUGTRACKER -> "BugTracker" to MaterialTheme.colorScheme.error
        ConnectionCapability.WIKI -> "Wiki" to MaterialTheme.colorScheme.tertiary
        ConnectionCapability.REPOSITORY -> "Repo" to MaterialTheme.colorScheme.primary
        ConnectionCapability.EMAIL_READ -> "Email Read" to MaterialTheme.colorScheme.secondary
        ConnectionCapability.EMAIL_SEND -> "Email Send" to MaterialTheme.colorScheme.secondary
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = color) },
    )
}

private val ConnectionResponseDto.displayUrl: String
    get() = baseUrl ?: host?.let { "$it${port?.let { port -> ":$port" } ?: ""}" } ?: "Bez adresy"

// ── Create Dialog ──────────────────────────────────────────────────────────────

@Composable
private fun ConnectionCreateDialog(
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onDismiss: () -> Unit,
    onCreate: (ConnectionCreateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(ProviderEnum.GITHUB) }

    val descriptor = descriptors[provider]
    val authOptions = descriptor?.authOptions ?: emptyList()
    var selectedAuthOption by remember { mutableStateOf(authOptions.firstOrNull()) }

    // Field values keyed by FormFieldType
    val fieldValues = remember { mutableStateMapOf<FormFieldType, String>() }

    // Reset auth option and field values when provider changes
    LaunchedEffect(provider) {
        val newAuthOptions = descriptors[provider]?.authOptions ?: emptyList()
        selectedAuthOption = newAuthOptions.firstOrNull()
        fieldValues.clear()
        // Initialize defaults
        selectedAuthOption?.fields?.forEach { field ->
            if (field.defaultValue.isNotEmpty()) {
                fieldValues[field.type] = field.defaultValue
            }
        }
    }

    // Reset field values when auth option changes
    LaunchedEffect(selectedAuthOption) {
        fieldValues.clear()
        selectedAuthOption?.fields?.forEach { field ->
            if (field.defaultValue.isNotEmpty()) {
                fieldValues[field.type] = field.defaultValue
            }
        }
    }

    val authType = selectedAuthOption?.authType ?: AuthTypeEnum.NONE
    val fields = selectedAuthOption?.fields ?: emptyList()
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    // Validation: all required fields must be non-blank (CLOUD_TOGGLE and USE_SSL are always valid)
    val enabled = name.isNotBlank() && fields.all { field ->
        !field.required ||
            field.type == FormFieldType.CLOUD_TOGGLE ||
            field.type == FormFieldType.USE_SSL ||
            field.type == FormFieldType.PROTOCOL ||
            // BASE_URL is not required when cloud toggle is on
            (field.type == FormFieldType.BASE_URL && isCloud) ||
            fieldValues[field.type]?.isNotBlank() == true
    }

    JFormDialog(
        visible = true,
        title = "Vytvořit nové připojení",
        onConfirm = {
            val protocol = fieldValues[FormFieldType.PROTOCOL]?.let { ProtocolEnum.valueOf(it) }
                ?: descriptor?.protocols?.firstOrNull() ?: ProtocolEnum.HTTP
            val request = ConnectionCreateRequestDto(
                provider = provider,
                protocol = protocol,
                authType = authType,
                name = name,
                isCloud = isCloud,
                baseUrl = fieldValues[FormFieldType.BASE_URL]?.takeIf { it.isNotBlank() },
                username = fieldValues[FormFieldType.USERNAME]?.takeIf { it.isNotBlank() },
                password = fieldValues[FormFieldType.PASSWORD]?.takeIf { it.isNotBlank() },
                bearerToken = fieldValues[FormFieldType.BEARER_TOKEN]?.takeIf { it.isNotBlank() },
                host = fieldValues[FormFieldType.HOST]?.takeIf { it.isNotBlank() },
                port = fieldValues[FormFieldType.PORT]?.toIntOrNull(),
                useSsl = fieldValues[FormFieldType.USE_SSL]?.let { it == "true" },
                folderName = fieldValues[FormFieldType.FOLDER_NAME]?.takeIf { it.isNotBlank() },
            )
            onCreate(request)
        },
        onDismiss = onDismiss,
        confirmEnabled = enabled,
        confirmText = if (authType == AuthTypeEnum.OAUTH2) "Authorizovat" else "Vytvořit",
    ) {
        // Connection name
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název připojení",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Provider dropdown
        ProviderDropdown(
            selected = provider,
            descriptors = descriptors,
            onSelect = { provider = it },
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Auth option dropdown (if multiple)
        if (authOptions.size > 1) {
            AuthOptionDropdown(
                selected = selectedAuthOption,
                options = authOptions,
                onSelect = { selectedAuthOption = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // OAuth2 info text when no fields
        if (authType == AuthTypeEnum.OAUTH2 && fields.isEmpty()) {
            Text(
                "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Render fields from auth option definition
        FormFields(
            fields = fields,
            fieldValues = fieldValues,
            availableProtocols = descriptor?.protocols ?: setOf(ProtocolEnum.HTTP),
        )
    }
}

// ── Edit Dialog ────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionEditDialog(
    connection: ConnectionResponseDto,
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onDismiss: () -> Unit,
    onSave: (String, ConnectionUpdateRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf(connection.name) }
    val state = connection.state
    var provider by remember { mutableStateOf(connection.provider) }

    val descriptor = descriptors[provider]
    val authOptions = descriptor?.authOptions ?: emptyList()
    var selectedAuthOption by remember {
        mutableStateOf(authOptions.firstOrNull { it.authType == connection.authType } ?: authOptions.firstOrNull())
    }

    // Field values initialized from connection
    val fieldValues = remember {
        mutableStateMapOf<FormFieldType, String>().apply {
            connection.baseUrl?.let { put(FormFieldType.BASE_URL, it) }
            connection.username?.let { put(FormFieldType.USERNAME, it) }
            connection.password?.let { put(FormFieldType.PASSWORD, it) }
            connection.bearerToken?.let { put(FormFieldType.BEARER_TOKEN, it) }
            connection.host?.let { put(FormFieldType.HOST, it) }
            connection.port?.let { put(FormFieldType.PORT, it.toString()) }
            connection.useSsl?.let { put(FormFieldType.USE_SSL, it.toString()) }
            connection.folderName?.let { put(FormFieldType.FOLDER_NAME, it) }
            put(FormFieldType.PROTOCOL, connection.protocol.name)
            put(FormFieldType.CLOUD_TOGGLE, connection.isCloud.toString())
        }
    }

    val authType = selectedAuthOption?.authType ?: AuthTypeEnum.NONE
    val fields = selectedAuthOption?.fields ?: emptyList()
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    val enabled = name.isNotBlank() && fields.all { field ->
        !field.required ||
            field.type == FormFieldType.CLOUD_TOGGLE ||
            field.type == FormFieldType.USE_SSL ||
            field.type == FormFieldType.PROTOCOL ||
            (field.type == FormFieldType.BASE_URL && isCloud) ||
            fieldValues[field.type]?.isNotBlank() == true
    }

    JFormDialog(
        visible = true,
        title = "Upravit připojení",
        onConfirm = {
            val protocol = fieldValues[FormFieldType.PROTOCOL]?.let { ProtocolEnum.valueOf(it) }
                ?: descriptor?.protocols?.firstOrNull() ?: ProtocolEnum.HTTP
            val request = ConnectionUpdateRequestDto(
                name = name,
                provider = provider,
                protocol = protocol,
                authType = authType,
                state = state,
                isCloud = isCloud,
                baseUrl = fieldValues[FormFieldType.BASE_URL]?.takeIf { it.isNotBlank() },
                username = fieldValues[FormFieldType.USERNAME]?.takeIf { it.isNotBlank() },
                password = fieldValues[FormFieldType.PASSWORD]?.takeIf { it.isNotBlank() },
                bearerToken = fieldValues[FormFieldType.BEARER_TOKEN]?.takeIf { it.isNotBlank() },
                host = fieldValues[FormFieldType.HOST]?.takeIf { it.isNotBlank() },
                port = fieldValues[FormFieldType.PORT]?.toIntOrNull(),
                useSsl = fieldValues[FormFieldType.USE_SSL]?.let { it == "true" },
                folderName = fieldValues[FormFieldType.FOLDER_NAME]?.takeIf { it.isNotBlank() },
            )
            onSave(connection.id, request)
        },
        onDismiss = onDismiss,
        confirmEnabled = enabled,
        confirmText = "Uložit",
    ) {
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název připojení",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))

        ProviderDropdown(
            selected = provider,
            descriptors = descriptors,
            onSelect = { provider = it },
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (authOptions.size > 1) {
            AuthOptionDropdown(
                selected = selectedAuthOption,
                options = authOptions,
                onSelect = { selectedAuthOption = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (authType == AuthTypeEnum.OAUTH2 && fields.isEmpty()) {
            Text(
                "Přihlašovací údaje pro OAuth2 jsou spravovány automaticky serverem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        FormFields(
            fields = fields,
            fieldValues = fieldValues,
            availableProtocols = descriptor?.protocols ?: setOf(ProtocolEnum.HTTP),
        )
    }
}

// ── Shared form components ─────────────────────────────────────────────────────

@Composable
private fun ProviderDropdown(
    selected: ProviderEnum,
    descriptors: Map<ProviderEnum, ProviderDescriptor>,
    onSelect: (ProviderEnum) -> Unit,
) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    JDropdownRaw(
        value = descriptors[selected]?.displayName ?: selected.name,
        label = "Provider",
        items = ProviderEnum.entries.toList(),
        itemLabel = { descriptors[it]?.displayName ?: it.name },
        onSelect = onSelect,
    )
}

@Composable
private fun AuthOptionDropdown(
    selected: AuthOption?,
    options: List<AuthOption>,
    onSelect: (AuthOption) -> Unit,
) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    JDropdownRaw(
        value = selected?.displayName ?: "",
        label = "Metoda autentizace",
        items = options,
        itemLabel = { it.displayName },
        onSelect = onSelect,
    )
}

/**
 * Generic dropdown backed by ExposedDropdownMenuBox.
 * Used for ProviderEnum and AuthOption where we need raw control.
 * Cannot use JTextField because menuAnchor() must be on the OutlinedTextField directly.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> JDropdownRaw(
    value: String,
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FormFields(
    fields: List<com.jervis.dto.connection.FormField>,
    fieldValues: MutableMap<FormFieldType, String>,
    availableProtocols: Set<ProtocolEnum>,
) {
    val isCloud = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true"

    for (field in fields) {
        when (field.type) {
            FormFieldType.CLOUD_TOGGLE -> {
                JCheckboxRow(
                    label = field.label,
                    checked = fieldValues[FormFieldType.CLOUD_TOGGLE] == "true",
                    onCheckedChange = { fieldValues[FormFieldType.CLOUD_TOGGLE] = it.toString() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FormFieldType.BASE_URL -> {
                // Hide base URL when cloud toggle is on
                if (!isCloud) {
                    JTextField(
                        value = fieldValues[FormFieldType.BASE_URL] ?: "",
                        onValueChange = { fieldValues[FormFieldType.BASE_URL] = it },
                        label = field.label,
                        placeholder = field.placeholder.ifEmpty { null },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            FormFieldType.USE_SSL -> {
                JCheckboxRow(
                    label = field.label,
                    checked = fieldValues[FormFieldType.USE_SSL] != "false",
                    onCheckedChange = { fieldValues[FormFieldType.USE_SSL] = it.toString() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            FormFieldType.PROTOCOL -> {
                if (availableProtocols.size > 1) {
                    val currentProtocol = fieldValues[FormFieldType.PROTOCOL]
                        ?: availableProtocols.firstOrNull()?.name ?: ""
                    JDropdownRaw(
                        value = currentProtocol,
                        label = field.label,
                        items = availableProtocols.toList(),
                        itemLabel = { it.name },
                        onSelect = { fieldValues[FormFieldType.PROTOCOL] = it.name },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            else -> {
                // Text fields: USERNAME, PASSWORD, BEARER_TOKEN, HOST, PORT, FOLDER_NAME
                JTextField(
                    value = fieldValues[field.type] ?: "",
                    onValueChange = { fieldValues[field.type] = it },
                    label = field.label,
                    placeholder = field.placeholder.ifEmpty { null },
                    singleLine = true,
                    visualTransformation = if (field.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
