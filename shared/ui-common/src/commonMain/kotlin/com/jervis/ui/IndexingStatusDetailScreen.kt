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

    LaunchedEffect(toolKey) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Indexing • ${'$'}toolKey") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } },
                actions = { com.jervis.ui.util.RefreshIconButton(onClick = { load() }) }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Failed to load: ${'$'}error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { load() }) { Text("Retry") }
                }
                detail == null -> {}
                else -> {
                    val d = detail!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Summary card
                        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(d.summary.displayName, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text("State: ${'$'}{d.summary.state}")
                                Text("Processed: ${'$'}{d.summary.processed} • Errors: ${'$'}{d.summary.errors}")
                                d.summary.lastError?.let { Text("Last error: ${'$'}it", color = MaterialTheme.colorScheme.error) }
                                Spacer(Modifier.height(4.dp))
                                val started = d.summary.lastRunStartedAt ?: "-"
                                val finished = d.summary.lastRunFinishedAt ?: "-"
                                Text("Last run: ${'$'}started → ${'$'}finished")
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
    }
}

@Composable
private fun ItemRow(item: IndexingItemDto) {
    val color = when (item.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "PROGRESS" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${'$'}{item.timestamp} • ${'$'}{item.level}", color = color, style = MaterialTheme.typography.labelSmall)
        Text(item.message, style = MaterialTheme.typography.bodyMedium)
        val deltas = listOfNotNull(
            item.processedDelta?.let { "+${'$'}it processed" },
            item.errorsDelta?.let { "+${'$'}it errors" }
        )
        if (deltas.isNotEmpty()) {
            Text(deltas.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}
