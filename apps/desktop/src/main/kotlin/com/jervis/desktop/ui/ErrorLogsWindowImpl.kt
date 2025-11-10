package com.jervis.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.error.ErrorLogDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Error Logs Window - Migrated from Swing to Compose Desktop
 * Displays server error logs with filtering, copying, and deletion capabilities
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogsWindowContent(repository: JervisRepository) {
    var errorLogs by remember { mutableStateOf<List<ErrorLogDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedLogId by remember { mutableStateOf<String?>(null) }
    var currentClientId by remember { mutableStateOf<String?>(null) }
    var showClientSelector by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }

    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    // Load clients on mount
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                clients = repository.clients.listClients()
                // Auto-select first client if available
                if (clients.isNotEmpty() && currentClientId == null) {
                    currentClientId = clients.first().id
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load clients: ${e.message}"
            }
        }
    }

    // Load error logs when client changes
    fun loadErrorLogs() {
        val clientId = currentClientId
        if (clientId == null) {
            showClientSelector = true
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                errorLogs = repository.errorLogs.listErrorLogs(clientId, 500)
            } catch (e: Exception) {
                errorMessage = "Failed to load error logs: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Auto-load when client is selected
    LaunchedEffect(currentClientId) {
        if (currentClientId != null) {
            loadErrorLogs()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Server Error Logs")
                        currentClientId?.let { clientId ->
                            val clientName = clients.find { it.id == clientId }?.name ?: clientId
                            Text(
                                text = "Client: $clientName",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showClientSelector = true }) {
                        Icon(Icons.Default.Person, "Select Client")
                    }
                    IconButton(onClick = { loadErrorLogs() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                currentClientId == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Please select a client first")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showClientSelector = true }) {
                            Text("Select Client")
                        }
                    }
                }
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = { loadErrorLogs() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
                errorLogs.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No error logs for this client")
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val id = selectedLogId
                                    if (id == null) {
                                        errorMessage = "Select a row first"
                                        return@Button
                                    }
                                    scope.launch {
                                        try {
                                            val log = repository.errorLogs.getErrorLog(id)
                                            val text = buildString {
                                                appendLine("ID: ${log.id}")
                                                appendLine("Timestamp: ${log.createdAt}")
                                                appendLine("Message: ${log.message}")
                                                log.causeType?.let { appendLine("Cause Type: $it") }
                                                log.stackTrace?.let { appendLine("\nStack Trace:\n$it") }
                                            }
                                            clipboardManager.setClip(androidx.compose.ui.platform.ClipEntry(text))
                                            errorMessage = "Copied to clipboard"
                                        } catch (e: Exception) {
                                            errorMessage = "Failed to copy: ${e.message}"
                                        }
                                    }
                                },
                                enabled = selectedLogId != null
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Selected")
                            }

                            Button(
                                onClick = {
                                    val id = selectedLogId
                                    if (id == null) {
                                        errorMessage = "Select a row first"
                                        return@Button
                                    }
                                    scope.launch {
                                        try {
                                            repository.errorLogs.deleteErrorLog(id)
                                            selectedLogId = null
                                            loadErrorLogs()
                                        } catch (e: Exception) {
                                            errorMessage = "Failed to delete: ${e.message}"
                                        }
                                    }
                                },
                                enabled = selectedLogId != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete Selected")
                            }

                            Button(
                                onClick = { showConfirmDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete All for Client")
                            }
                        }

                        // Error logs table
                        ErrorLogsTable(
                            errorLogs = errorLogs,
                            selectedLogId = selectedLogId,
                            onRowSelected = { selectedLogId = it }
                        )
                    }
                }
            }
        }
    }

    // Client selector dialog
    if (showClientSelector) {
        ClientSelectorDialog(
            clients = clients,
            currentClientId = currentClientId,
            onClientSelected = { clientId ->
                currentClientId = clientId
                showClientSelector = false
            },
            onDismiss = { showClientSelector = false }
        )
    }

    // Confirm delete all dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Confirm Delete") },
            text = { Text("Really delete all error logs for this client?") },
            confirmButton = {
                Button(
                    onClick = {
                        currentClientId?.let { clientId ->
                            scope.launch {
                                try {
                                    repository.errorLogs.deleteAllForClient(clientId)
                                    showConfirmDialog = false
                                    loadErrorLogs()
                                } catch (e: Exception) {
                                    errorMessage = "Failed to delete all: ${e.message}"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ErrorLogsTable(
    errorLogs: List<ErrorLogDto>,
    selectedLogId: String?,
    onRowSelected: (String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Timestamp",
                        modifier = Modifier.weight(0.25f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = "Message",
                        modifier = Modifier.weight(0.65f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = "Type",
                        modifier = Modifier.weight(0.1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }

        // Rows
        items(errorLogs) { log ->
            val isSelected = selectedLogId == log.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    onRowSelected(if (isSelected) null else log.id)
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (isSelected) 4.dp else 1.dp
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = log.createdAt,
                        modifier = Modifier.weight(0.25f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = log.message,
                        modifier = Modifier.weight(0.65f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    log.causeType?.let { causeType ->
                        Text(
                            text = causeType.substringAfterLast('.'),
                            modifier = Modifier.weight(0.1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientSelectorDialog(
    clients: List<ClientDto>,
    currentClientId: String?,
    onClientSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Client") },
        text = {
            LazyColumn {
                items(clients) { client ->
                    val isSelected = client.id == currentClientId
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            client.id?.let { onClientSelected(it) }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = client.name ?: "Unnamed Client",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, "Selected")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
