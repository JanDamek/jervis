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
    // No client selection needed for Jira manual run anymore
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

    fun runJira() {
        scope.launch {
            try {
                repository.indexingStatus.runJiraNow()
                infoMessage = "Triggered Jira indexing (auto-select next connection)"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to trigger Jira indexing: ${t.message}"
            }
        }
    }

    fun runEmail() {
        scope.launch {
            try {
                repository.indexingStatus.runEmailNow()
                infoMessage = "Triggered Email indexing (auto-select next account)"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to trigger Email indexing: ${t.message}"
            }
        }
    }

    fun runGit() {
        scope.launch {
            try {
                repository.indexingStatus.runGitNow()
                infoMessage = "Triggered Git synchronization"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to trigger Git synchronization: ${t.message}"
            }
        }
    }

    fun runConfluence() {
        scope.launch {
            try {
                repository.indexingStatus.runConfluenceNow()
                infoMessage = "Triggered Confluence sync (auto-select next account)"
                load()
            } catch (t: Throwable) {
                infoMessage = "Failed to trigger Confluence sync: ${t.message}"
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
                                        if (s.state.name != "RUNNING") {
                                            Spacer(Modifier.width(8.dp))
                                            when (s.toolKey) {
                                                "jira" -> TextButton(onClick = { runJira() }) { Text("▶ Run") }
                                                "email" -> TextButton(onClick = { runEmail() }) { Text("▶ Run") }
                                                "git" -> TextButton(onClick = { runGit() }) { Text("▶ Run") }
                                                "confluence" -> TextButton(onClick = { runConfluence() }) { Text("▶ Run") }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    s.reason?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "✓ Processed: ${s.processed}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (s.errors > 0) {
                                            Text(
                                                "⚠ Errors: ${s.errors}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                    // Detailed error message if present
                                    s.lastError?.let { errorMsg ->
                                        Spacer(Modifier.height(8.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                            ),
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        "⚠",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                    Text(
                                                        "Last Error:",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    errorMsg,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                    if (s.state.name == "RUNNING") {
                                        s.runningSince?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "Running since: $it",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }

        infoMessage?.let { Text(it) }
    }
}
