package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.indexing.IndexingToolSummaryDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexingStatusScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
    onOpenDetail: (toolKey: String) -> Unit,
) {
    var summaries by remember { mutableStateOf<List<IndexingToolSummaryDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runDialogClientId by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.indexingStatus.overview() }
                .onSuccess { summaries = it.tools }
                .onFailure { error = it.message }
            isLoading = false
        }
    }

    fun runJira(clientId: String) {
        scope.launch {
            try {
                repository.indexingStatus.runJiraNow(clientId)
                infoMessage = "Triggered Jira indexing for client=$clientId"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to trigger Jira indexing: ${t.message}"
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Indexing Status") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { load() })
                }
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null ->
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Failed to load: $error", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { load() }) { Text("Retry") }
                    }
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        items(summaries) { s ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onOpenDetail(s.toolKey) },
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.width(8.dp))
                                        val badge = if (s.state.name == "RUNNING") "RUNNING" else "IDLE"
                                        AssistChip(onClick = { onOpenDetail(s.toolKey) }, label = { Text(badge) })
                                        if (s.toolKey == "jira") {
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(onClick = {
                                                // Try to prefill clientId from reason text if present
                                                val regex = Regex("client=([0-9a-fA-F]{24})")
                                                val found = s.reason?.let { regex.find(it)?.groupValues?.getOrNull(1) }
                                                runDialogClientId = found ?: ""
                                            }) { Text("▶ Run") }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    s.reason?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Processed: ${s.processed}  •  Errors: ${s.errors}")
                                    s.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
            }
        }

        // Run dialog
        if (runDialogClientId != null) {
            var tmp by remember { mutableStateOf(runDialogClientId ?: "") }
            AlertDialog(
                onDismissRequest = { runDialogClientId = null },
                title = { Text("Trigger Jira indexing") },
                text = {
                    Column {
                        Text("Enter clientId to trigger indexing for:")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = tmp, onValueChange = { tmp = it }, label = { Text("clientId") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runDialogClientId = null
                        runJira(tmp)
                    }) { Text("Run") }
                },
                dismissButton = { TextButton(onClick = { runDialogClientId = null }) { Text("Cancel") } },
            )
        }

        infoMessage?.let { Text(it) }
    }
}
