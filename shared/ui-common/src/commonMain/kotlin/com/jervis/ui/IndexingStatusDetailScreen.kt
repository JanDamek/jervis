package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.indexing.IndexingItemDto
import com.jervis.dto.indexing.IndexingToolDetailDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexingStatusDetailScreen(
    repository: JervisRepository,
    toolKey: String,
    onBack: () -> Unit,
) {
    var detail by remember { mutableStateOf<IndexingToolDetailDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runDialogClientId by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.indexingStatus.detail(toolKey) }
                .onSuccess { detail = it }
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

    LaunchedEffect(toolKey) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Indexing • $toolKey") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } },
                actions = {
                    TextButton(onClick = { load() }) { Text("🔄 Refresh") }
                    if (toolKey == "jira") {
                        TextButton(onClick = {
                            // Try to prefill clientId from loaded detail
                            val found =
                                detail?.summary?.reason?.let {
                                    Regex(
                                        "client=([0-9a-fA-F]{24})",
                                    ).find(it)?.groupValues?.getOrNull(1)
                                }
                            runDialogClientId = found ?: ""
                        }) { Text("▶ Run") }
                    }
                },
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
                detail == null -> {}
                else -> {
                    val d = detail!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Summary card
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(
                                    d.summary.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                d.summary.reason?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val badgeColor = if (d.summary.state.name == "RUNNING")
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.secondary
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(d.summary.state.name) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = badgeColor,
                                        ),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        "✓ Processed: ${d.summary.processed}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (d.summary.errors > 0) {
                                        Text(
                                            "⚠ Errors: ${d.summary.errors}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                d.summary.lastError?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Last error: $it",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                val started = d.summary.lastRunStartedAt ?: "-"
                                val finished = d.summary.lastRunFinishedAt ?: "-"
                                Text(
                                    "Last run: $started → $finished",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Items
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            items(d.items) { item -> ItemRow(item) }
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

@Composable
private fun ItemRow(item: IndexingItemDto) {
    val (levelIcon, levelColor, containerColor) =
        when (item.level) {
            "ERROR" -> Triple("⚠", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
            "PROGRESS" -> Triple("⟳", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            else -> Triple("ℹ", MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant)
        }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    levelIcon,
                    color = levelColor,
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.level,
                        color = levelColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        item.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            val deltas =
                listOfNotNull(
                    item.processedDelta?.let { "+$it processed" },
                    item.errorsDelta?.let { "+$it errors" },
                )
            if (deltas.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    deltas.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
