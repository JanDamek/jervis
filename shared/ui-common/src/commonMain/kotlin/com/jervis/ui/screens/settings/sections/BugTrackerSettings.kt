package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.bugtracker.BugTrackerSetupStatusDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.SettingCard
import com.jervis.ui.components.StatusIndicator
import kotlinx.coroutines.launch

@Composable
fun BugTrackerSettings(repository: JervisRepository) {
    var clientsWithStatus by remember { mutableStateOf<List<Pair<String, BugTrackerSetupStatusDto>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                 val clients = repository.clients.getAllClients()
                clientsWithStatus = clients.map { client ->
                    client.name to repository.bugTrackerSetup.getStatus(client.id)
                }
            } catch (e: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (isLoading && clientsWithStatus.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(clientsWithStatus.size) { index ->
                val (clientName, status) = clientsWithStatus[index]
                SettingCard(title = "Klient: $clientName") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BugTracker Status:", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            StatusIndicator(if (status.connected) "CONNECTED" else "DISCONNECTED")
                        }
                    
                        if (status.connected) {
                            Text("Tenant: ${status.tenant ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                            Text("Email: ${status.email ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                            Text("Primární Projekt: ${status.primaryProject ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
