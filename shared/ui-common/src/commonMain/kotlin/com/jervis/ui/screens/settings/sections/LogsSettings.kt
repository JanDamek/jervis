package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.error.ErrorLogDto
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTableRowCard
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
fun LogsSettings(repository: JervisRepository) {
    var logs by remember { mutableStateOf<List<ErrorLogDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                logs = repository.errorLogs.listAllErrorLogs(100)
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

        if (isLoading && logs.isEmpty()) {
            JCenteredLoading()
        } else if (logs.isEmpty()) {
            JEmptyState(message = "Žádné chybové logy nenalezeny")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { log ->
                    JTableRowCard(selected = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                log.createdAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            log.causeType?.let {
                                Text(
                                    it.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(log.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
