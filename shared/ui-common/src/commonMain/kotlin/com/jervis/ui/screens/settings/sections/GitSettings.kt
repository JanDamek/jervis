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
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.RefreshIconButton
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

    Column(modifier = Modifier.fillMaxSize()) {
        JActionBar {
            RefreshIconButton(onClick = { loadData() })
        }

        Spacer(Modifier.height(8.dp))

        if (isLoading && clients.isEmpty()) {
            JCenteredLoading()
        } else if (clients.isEmpty()) {
            JEmptyState(message = "Žádní klienti nenalezeni", icon = "🏢")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(clients) { client ->
                    JSection(title = "Klient: ${client.name}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Přiřazená připojení: ${client.connectionIds.size}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
