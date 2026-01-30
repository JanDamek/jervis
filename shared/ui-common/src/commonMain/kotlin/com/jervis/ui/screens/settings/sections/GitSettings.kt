package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.SettingCard
import kotlinx.coroutines.launch

@Composable
fun GitSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                clients = repository.clients.listClients()
            } catch (e: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (isLoading && clients.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(clients) { client ->
                SettingCard(title = "Klient: ${client.name}") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Provider: ${client.gitProvider ?: "Nenastaveno"}", style = MaterialTheme.typography.bodyMedium)
                        Text("Auth Type: ${client.gitAuthType ?: "Nenastaveno"}", style = MaterialTheme.typography.bodySmall)
                        
                        val config = client.gitConfig
                        if (config != null) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            val userNameValue = config.gitUserName ?: "N/A"
                            Text("User: $userNameValue", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
