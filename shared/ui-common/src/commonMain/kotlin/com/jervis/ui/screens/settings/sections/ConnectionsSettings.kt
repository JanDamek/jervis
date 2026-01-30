package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.connection.ConnectionResponseDto
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.StatusIndicator
import kotlinx.coroutines.launch

@Composable
fun ConnectionsSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                connections = repository.connections.listConnections()
                clients = repository.clients.listClients()
            } catch (e: Exception) {
                // handle error
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = { loadData() }) {
                    Text("⟳ Načíst")
                }
                Button(onClick = { /* Create */ }) {
                    Text("+ Přidat připojení")
                }
            }

            if (isLoading && connections.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
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
                                            if (result.success) "Test OK" else "Test selhal: ${result.message}"
                                        )
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba testu: ${e.message}")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )
    }
}

@Composable
private fun ConnectionItemCard(
    connection: ConnectionResponseDto,
    clients: List<ClientDto>,
    onTest: () -> Unit
) {
    val assignedClient = clients.firstOrNull { it.connectionIds.contains(connection.id) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(connection.type, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Text(
                    text = connection.baseUrl ?: connection.host ?: "Bez adresy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assignedClient != null) {
                    Text(
                        text = "Používá: ${assignedClient.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            StatusIndicator(connection.state.name)
            
            Row(modifier = Modifier.padding(start = 16.dp)) {
                Button(onClick = onTest) {
                    Text("▶")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { /* Edit */ }) {
                    Text("✎")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { /* Delete */ }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("🗑")
                }
            }
        }
    }
}
