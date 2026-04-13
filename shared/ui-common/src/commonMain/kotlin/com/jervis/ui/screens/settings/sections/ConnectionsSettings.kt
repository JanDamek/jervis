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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jervis.dto.client.ClientDto
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.BrowserSessionStatusDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.di.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.RefreshIconButton
import com.jervis.ui.util.openUrlInBrowser
import com.jervis.ui.util.openUrlInPrivateBrowser
import com.jervis.ui.LocalRpcGeneration
import kotlinx.coroutines.delay
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
    var showTeamsLoginDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }
    var showWhatsAppLoginDialog by remember { mutableStateOf<ConnectionResponseDto?>(null) }

    suspend fun loadConnections() {
        try {
            connections = repository.connections.getAllConnections()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání připojení: ${e.message}")
        }
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) {
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
                                onReauthorizePrivate = {
                                    scope.launch {
                                        try {
                                            val authUrl = repository.connections.initiateOAuth2(connection.id, forceLogin = true)
                                            openUrlInPrivateBrowser(authUrl)
                                            snackbarHostState.showSnackbar("Otevřeno v privátním okně. Přihlaste se jako jiný uživatel.")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                        }
                                    }
                                },
                                onTeamsLogin = { showTeamsLoginDialog = connection },
                                onWhatsAppLogin = { showWhatsAppLoginDialog = connection },
                                onRediscover = {
                                    scope.launch {
                                        try {
                                            repository.connections.rediscoverCapabilities(connection.id)
                                            snackbarHostState.showSnackbar("Zjišťuji dostupné služby...")
                                            // Rediscovery is async — browser pod re-checks tabs and
                                            // pushes capabilities via callback. Wait before refresh.
                                            kotlinx.coroutines.delay(8000)
                                            loadConnections()
                                            snackbarHostState.showSnackbar("Služby aktualizovány")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                            loadConnections()
                                        }
                                    }
                                },
                                onVnc = {
                                    // Both O365 and WhatsApp: get VNC URL with one-time auth token
                                    scope.launch {
                                        try {
                                            snackbarHostState.showSnackbar("Připojuji k VNC...")
                                            val status = repository.connections.getBrowserSessionStatus(connection.id)
                                            val url = status.vncUrl
                                            if (!url.isNullOrBlank()) {
                                                openUrlInBrowser(url)
                                            } else {
                                                snackbarHostState.showSnackbar("VNC: session není aktivní (${status.state})")
                                            }
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("VNC: ${e.message}")
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
                                // Browser Session: server auto-inits browser pool, show login dialog
                                showTeamsLoginDialog = created
                            }
                            request.provider == com.jervis.dto.connection.ProviderEnum.WHATSAPP &&
                                request.authType == AuthTypeEnum.NONE -> {
                                // WhatsApp Browser Session: show QR code login dialog
                                showWhatsAppLoginDialog = created
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
                        showEditDialog = null
                        // Auto-test connection after save — validates credentials
                        // and transitions to VALID/INVALID immediately so the user
                        // sees the result without manually clicking "Test".
                        try {
                            val result = repository.connections.testConnection(id)
                            val statusMsg = if (result.success) "✓ Připojení ověřeno" else "✗ Test selhal: ${result.message}"
                            snackbarHostState.showSnackbar(statusMsg)
                        } catch (_: Exception) {
                            snackbarHostState.showSnackbar("Připojení uloženo (test se nepodařil)")
                        }
                        loadConnections()
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

    showTeamsLoginDialog?.let { connection ->
        TeamsLoginDialog(
            connection = connection,
            repository = repository,
            onDismiss = {
                showTeamsLoginDialog = null
                scope.launch { loadConnections() }
            },
        )
    }

    showWhatsAppLoginDialog?.let { connection ->
        WhatsAppLoginDialog(
            connection = connection,
            repository = repository,
            onDismiss = {
                showWhatsAppLoginDialog = null
                scope.launch { loadConnections() }
            },
        )
    }
}

@Composable
private fun ConnectionItemCard(
    connection: ConnectionResponseDto,
    clients: List<ClientDto>,
    onTest: () -> Unit,
    onReauthorize: () -> Unit,
    onReauthorizePrivate: () -> Unit,
    onTeamsLogin: () -> Unit,
    onWhatsAppLogin: () -> Unit,
    onRediscover: () -> Unit,
    onVnc: () -> Unit,
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
            if (connection.isJervisOwned) {
                Spacer(Modifier.width(4.dp))
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text("🤖 Jervis", style = MaterialTheme.typography.labelSmall)
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            JStatusBadge(status = connection.state.name)
        }

        // AUTH_EXPIRED warning
        if (connection.state == ConnectionStateEnum.AUTH_EXPIRED) {
            val isBrowserSession = connection.provider == com.jervis.dto.connection.ProviderEnum.MICROSOFT_TEAMS &&
                connection.authType == AuthTypeEnum.NONE
            val isWhatsApp = connection.provider == com.jervis.dto.connection.ProviderEnum.WHATSAPP
            Text(
                text = if (isBrowserSession) {
                    if (isWhatsApp) "⚠ Session vypršela — naskenujte QR kód znovu"
                    else "⚠ Přihlášení vypršelo — klikněte na Přihlásit k Teams"
                } else {
                    "⚠ Token expiroval — obnovte přes Re-auth tlačítko"
                },
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
        // Self-identity info
        connection.selfUsername?.let { username ->
            val identityText = buildString {
                append("Identita: @$username")
                connection.selfDisplayName?.let { append(" ($it)") }
            }
            Text(
                text = identityText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Capabilities — show loading state while DISCOVERING
        Spacer(Modifier.height(8.dp))
        when {
            connection.state == ConnectionStateEnum.DISCOVERING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Zjišťuji dostupné služby...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            connection.capabilities.isNotEmpty() -> {
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
            connection.state == ConnectionStateEnum.VALID && connection.capabilities.isEmpty() -> {
                Text(
                    "Žádné služby nenalezeny",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Action buttons
        val isBrowserSession = (connection.provider == com.jervis.dto.connection.ProviderEnum.MICROSOFT_TEAMS ||
            connection.provider == com.jervis.dto.connection.ProviderEnum.WHATSAPP) &&
            connection.authType == AuthTypeEnum.NONE
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isBrowserSession) {
                // Graph API test — only for non-browser-session connections
                JPrimaryButton(onClick = onTest) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Test")
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
            }
            if (connection.authType == AuthTypeEnum.OAUTH2) {
                JPrimaryButton(onClick = onReauthorize) {
                    Text("Re-auth")
                }
                JSecondaryButton(onClick = onReauthorizePrivate) {
                    Text("Jiný účet")
                }
            }
            if (isBrowserSession && (
                    connection.state == ConnectionStateEnum.AUTH_EXPIRED ||
                    connection.state == ConnectionStateEnum.INVALID ||
                    connection.state == ConnectionStateEnum.NEW
                )
            ) {
                val isWhatsAppProvider = connection.provider == com.jervis.dto.connection.ProviderEnum.WHATSAPP
                JPrimaryButton(onClick = if (isWhatsAppProvider) onWhatsAppLogin else onTeamsLogin) {
                    Text(if (isWhatsAppProvider) "Připojit WhatsApp" else "Přihlásit k Teams")
                }
            }
            if (isBrowserSession && (
                    connection.state == ConnectionStateEnum.VALID ||
                    connection.state == ConnectionStateEnum.DISCOVERING
                )
            ) {
                JSecondaryButton(
                    onClick = onRediscover,
                    enabled = connection.state != ConnectionStateEnum.DISCOVERING,
                ) {
                    Text("Znovu zjistit služby")
                }
            }
            // VNC button for browser-session connections
            if (isBrowserSession) {
                JSecondaryButton(onClick = onVnc) {
                    Text("VNC")
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

/**
 * Teams Browser Session login dialog.
 * Guides user through Microsoft login via remote browser (noVNC).
 * Polls session status and auto-closes when token is captured.
 */
@Composable
private fun TeamsLoginDialog(
    connection: ConnectionResponseDto,
    repository: JervisRepository,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf<BrowserSessionStatusDto?>(null) }
    var vncUrl by remember { mutableStateOf<String?>(null) }
    var vncOpened by remember { mutableStateOf(false) }
    var tokenCaptured by remember { mutableStateOf(false) }
    var mfaCode by remember { mutableStateOf("") }
    var mfaSubmitting by remember { mutableStateOf(false) }
    var mfaError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Poll session status every 3 seconds
    LaunchedEffect(connection.id) {
        while (true) {
            try {
                val s = repository.connections.getBrowserSessionStatus(connection.id)
                status = s
                // Remember VNC URL once we get it
                if (s.vncUrl != null) vncUrl = s.vncUrl
                // Auto-close when state is ACTIVE
                if (s.state == "ACTIVE") {
                    tokenCaptured = true
                    delay(2000)
                    onDismiss()
                    return@LaunchedEffect
                }
            } catch (_: Exception) {
                // Ignore polling errors — keep dialog open
            }
            delay(3000)
        }
    }

    val hasCredentials = connection.username?.isNotBlank() == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přihlášení k Microsoft Teams") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    tokenCaptured -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            JStatusBadge(status = "VALID")
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Přihlášení úspěšné!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "Dialog se automaticky zavře.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    status?.state == "AWAITING_MFA" -> {
                        // MFA required — show code input
                        Text(
                            status?.mfaMessage ?: "Vyžadováno dvoufaktorové ověření",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (status?.mfaNumber != null) {
                            // Authenticator number matching — show the number prominently
                            Text(
                                status?.mfaNumber ?: "",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        if (status?.mfaType == "authenticator_code" || status?.mfaType == "sms_code") {
                            OutlinedTextField(
                                value = mfaCode,
                                onValueChange = { mfaCode = it.filter { c -> c.isDigit() }.take(8) },
                                label = { Text("Ověřovací kód") },
                                placeholder = { Text("123456") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !mfaSubmitting,
                            )
                        }
                        if (mfaError != null) {
                            Text(
                                mfaError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (mfaSubmitting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Ověřuji...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    status?.state == "PENDING_LOGIN" && hasCredentials -> {
                        // Auto-login in progress
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Přihlašuji se automaticky...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "Heslo bylo zadáno v nastavení připojení. Přihlášení probíhá automaticky.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    vncOpened -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Čekám na přihlášení k Microsoft...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "Dokončete přihlášení v otevřeném okně prohlížeče. " +
                                "Po úspěšném přihlášení se dialog automaticky zavře.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    status?.state == "ERROR" -> {
                        Text(
                            status?.message ?: "Chyba přihlášení",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Offer VNC fallback
                        Text(
                            "Můžete se přihlásit ručně přes prohlížeč.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            "Klikněte na tlačítko pro otevření přihlašovacího okna Microsoft.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Po přihlášení k Microsoft účtu bude token automaticky zachycen a dialog se zavře.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (status?.state == "EXPIRED") {
                            Text(
                                "Předchozí token expiroval — je nutné se znovu přihlásit.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (status == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Připravuji session...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                tokenCaptured -> {
                    TextButton(onClick = onDismiss) {
                        Text("Zavřít")
                    }
                }

                status?.state == "AWAITING_MFA" && (status?.mfaType == "authenticator_code" || status?.mfaType == "sms_code") -> {
                    JPrimaryButton(
                        onClick = {
                            mfaSubmitting = true
                            mfaError = null
                            scope.launch {
                                try {
                                    val result = repository.connections.submitBrowserSessionMfa(
                                        connection.id, mfaCode,
                                    )
                                    if (result.state == "ACTIVE") {
                                        tokenCaptured = true
                                    } else {
                                        mfaError = result.message ?: "Kód nebyl přijat"
                                        mfaCode = ""
                                    }
                                } catch (e: Exception) {
                                    mfaError = "Chyba: ${e.message}"
                                } finally {
                                    mfaSubmitting = false
                                }
                            }
                        },
                        enabled = mfaCode.length >= 4 && !mfaSubmitting,
                    ) {
                        Text("Ověřit")
                    }
                }

                else -> {
                    val url = vncUrl
                    JPrimaryButton(
                        onClick = {
                            if (url != null) {
                                openUrlInBrowser(url)
                                vncOpened = true
                            }
                        },
                        enabled = url != null,
                    ) {
                        Text(if (vncOpened) "Otevřít znovu" else "Otevřít přihlášení")
                    }
                }
            }
        },
        dismissButton = {
            if (!tokenCaptured) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
            }
        },
    )
}

/**
 * WhatsApp Browser Session login dialog.
 * Guides user through QR code scanning via remote browser (noVNC).
 * Polls session status and auto-closes when WhatsApp Web connects.
 */
@Composable
private fun WhatsAppLoginDialog(
    connection: ConnectionResponseDto,
    repository: JervisRepository,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf<BrowserSessionStatusDto?>(null) }
    var vncUrl by remember { mutableStateOf<String?>(null) }
    var vncOpened by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    // Poll session status every 3 seconds
    LaunchedEffect(connection.id) {
        while (true) {
            try {
                val s = repository.connections.getBrowserSessionStatus(connection.id)
                status = s
                if (s.vncUrl != null) vncUrl = s.vncUrl
                if (s.state == "ACTIVE") {
                    connected = true
                    delay(2000)
                    onDismiss()
                    return@LaunchedEffect
                }
            } catch (_: Exception) {
                // Ignore polling errors
            }
            delay(3000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Připojení WhatsApp") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    connected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            JStatusBadge(status = "VALID")
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "WhatsApp Web připojeno!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "Dialog se automaticky zavře.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    vncOpened -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Čekám na naskenování QR kódu...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "Otevřete WhatsApp na telefonu → Nastavení → Propojená zařízení → Propojit zařízení. " +
                                "Naskenujte QR kód v otevřeném okně prohlížeče. " +
                                "Po úspěšném připojení se dialog automaticky zavře.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    status?.state == "ERROR" -> {
                        Text(
                            status?.message ?: "Chyba připojení",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Text(
                            "Klikněte na tlačítko pro otevření WhatsApp Web s QR kódem.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Budete potřebovat telefon s nainstalovaným WhatsApp pro naskenování QR kódu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (status?.state == "EXPIRED") {
                            Text(
                                "Předchozí session expirovala — naskenujte QR kód znovu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (status == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Připravuji session...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (connected) {
                TextButton(onClick = onDismiss) {
                    Text("Zavřít")
                }
            } else {
                val url = vncUrl
                JPrimaryButton(
                    onClick = {
                        if (url != null) {
                            openUrlInBrowser(url)
                            vncOpened = true
                        }
                    },
                    enabled = url != null,
                ) {
                    Text(if (vncOpened) "Otevřít znovu" else "Otevřít QR kód")
                }
            }
        },
        dismissButton = {
            if (!connected) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
            }
        },
    )
}

internal val ConnectionResponseDto.displayUrl: String
    get() = baseUrl?.takeIf { it.isNotBlank() }
        ?: host?.let { "$it${port?.let { port -> ":$port" } ?: ""}" }
        ?: when (provider) {
            com.jervis.dto.connection.ProviderEnum.MICROSOFT_TEAMS -> when (authType) {
                AuthTypeEnum.OAUTH2 -> "Microsoft Graph API (OAuth2)"
                AuthTypeEnum.NONE -> "Browser Session (K8s pod)"
                AuthTypeEnum.BEARER -> "Lokální token"
                else -> "Microsoft Teams"
            }
            com.jervis.dto.connection.ProviderEnum.WHATSAPP -> "WhatsApp Web (Browser Session)"
            else -> "Bez adresy"
        }
