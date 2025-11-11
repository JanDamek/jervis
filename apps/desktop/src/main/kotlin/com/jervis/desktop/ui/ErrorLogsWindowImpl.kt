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

    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    fun loadErrorLogs() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                errorLogs = repository.errorLogs.listAllErrorLogs(500)
            } catch (e: Exception) {
                errorMessage = "Failed to load error logs: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) { loadErrorLogs() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Error Logs") },
                actions = {
                    IconButton(onClick = { loadErrorLogs() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
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
                        Text("No errors recorded")
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
