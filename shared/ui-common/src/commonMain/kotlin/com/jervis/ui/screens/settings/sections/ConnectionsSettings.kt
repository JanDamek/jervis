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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.RefreshIconButton
import com.jervis.ui.util.openUrlInBrowser
import kotlinx.coroutines.launch

@Composable
fun ConnectionsSettings(repository: JervisRepository) {
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()

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
                        when {
                            request.authType == AuthTypeEnum.OAUTH2 -> {
                                val authUrl = repository.connections.initiateOAuth2(created.id)
                                openUrlInBrowser(authUrl)
                                snackbarHostState.showSnackbar("OAuth2 autorizace byla spuštěna. Dokončete ji v prohlížeči.")
                            }
                            request.provider == com.jervis.dto.connection.ProviderEnum.MICROSOFT_TEAMS &&
                                request.authType == AuthTypeEnum.NONE -> {
                                // Browser Session: server auto-inits browser pool, show result
                                snackbarHostState.showSnackbar("Browser session inicializována. Dokončete přihlášení přes noVNC.")
                            }
                            else -> {
                                snackbarHostState.showSnackbar("Připojení vytvořeno")
                            }
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
                label = {
                    val providerLabel = ProviderDescriptor.defaultsByProvider[connection.provider]?.displayName
                        ?: connection.provider.name
                    Text(providerLabel, style = MaterialTheme.typography.labelSmall)
                },
            )
            Spacer(Modifier.width(8.dp))
            JStatusBadge(status = connection.state.name)
        }

        // AUTH_EXPIRED warning
        if (connection.state == ConnectionStateEnum.AUTH_EXPIRED) {
            Text(
                text = "⚠ Token expiroval — obnovte přes Re-auth tlačítko",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
        ConnectionCapability.REPOSITORY -> "Repozitář" to MaterialTheme.colorScheme.primary
        ConnectionCapability.BUGTRACKER -> "Úkoly" to MaterialTheme.colorScheme.error
        ConnectionCapability.WIKI -> "Wiki" to MaterialTheme.colorScheme.tertiary
        ConnectionCapability.EMAIL_READ -> "Čtení emailů" to MaterialTheme.colorScheme.secondary
        ConnectionCapability.EMAIL_SEND -> "Odesílání emailů" to MaterialTheme.colorScheme.secondary
        ConnectionCapability.CHAT_READ -> "Čtení chatu" to MaterialTheme.colorScheme.secondary
        ConnectionCapability.CHAT_SEND -> "Odesílání chatu" to MaterialTheme.colorScheme.secondary
        ConnectionCapability.CALENDAR_READ -> "Kalendář" to MaterialTheme.colorScheme.tertiary
        ConnectionCapability.CALENDAR_WRITE -> "Zápis do kalendáře" to MaterialTheme.colorScheme.tertiary
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = color) },
    )
}

internal val ConnectionResponseDto.displayUrl: String
    get() = baseUrl?.takeIf { it.isNotBlank() }
        ?: host?.let { "$it${port?.let { port -> ":$port" } ?: ""}" }
        ?: if (provider == com.jervis.dto.connection.ProviderEnum.MICROSOFT_TEAMS) {
            when (authType) {
                AuthTypeEnum.OAUTH2 -> "Microsoft Graph API (OAuth2)"
                AuthTypeEnum.NONE -> "Browser Session (K8s pod)"
                AuthTypeEnum.BEARER -> "Lokální token"
                else -> "Microsoft Teams"
            }
        } else {
            "Bez adresy"
        }
