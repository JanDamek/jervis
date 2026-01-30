package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.ActionRibbon
import com.jervis.ui.components.SettingCard
import kotlinx.coroutines.launch

@Composable
fun ClientsSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    val scope = rememberCoroutineScope()

    fun loadClients() {
        scope.launch {
            isLoading = true
            try {
                clients = repository.clients.listClients()
            } catch (e: Exception) {
                // Error handling can be added here
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadClients() }

    if (selectedClient != null) {
        ClientEditForm(
            client = selectedClient!!,
            onSave = { updated ->
                scope.launch {
                    try {
                        repository.clients.updateClient(updated.id, updated)
                        selectedClient = null
                        loadClients()
                    } catch (e: Exception) {
                    }
                }
            },
            onCancel = { selectedClient = null }
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(clients) { client ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedClient = client },
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(client.name, style = MaterialTheme.typography.titleMedium)
                            Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            item {
                Button(onClick = { /* New client */ }, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Přidat klienta")
                }
            }
        }
    }
}

@Composable
private fun ClientEditForm(
    client: ClientDto,
    onSave: (ClientDto) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(client.name) }
    // Using a separate scroll state for the form
    val scrollState = androidx.compose.foundation.rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
            SettingCard(title = "Základní údaje") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název klienta") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingCard(title = "Git konfigurace") {
                Text("Poskytovatel: ${client.gitProvider ?: "Nenastaveno"}")
                Text("Autorizace: ${client.gitAuthType ?: "Nenastaveno"}")
            }
        }

        ActionRibbon(
            onSave = { onSave(client.copy(name = name)) },
            onCancel = onCancel,
            saveEnabled = name.isNotBlank()
        )
    }
}

