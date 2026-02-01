package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.indexing.IndexingOverviewDto
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JSection
import com.jervis.ui.util.RefreshIconButton
import com.jervis.ui.components.StatusIndicator
import kotlinx.coroutines.launch

@Composable
fun IndexingSettings(repository: JervisRepository) {
    var overview by remember { mutableStateOf<IndexingOverviewDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                overview = repository.ragSearch.getIndexingOverview()
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

        if (isLoading && overview == null) {
            JCenteredLoading()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                overview?.tools?.let { tools ->
                    items(tools) { tool ->
                        JSection(title = tool.displayName) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    StatusIndicator(tool.state.name)
                                    Text(
                                        "Indexováno: ${tool.indexedCount} | Čeká: ${tool.newCount}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (tool.processed > 0 || tool.errors > 0) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            "Poslední běh: ${tool.processed} ok, ${tool.errors} chyb",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (tool.state.name == "RUNNING") {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
