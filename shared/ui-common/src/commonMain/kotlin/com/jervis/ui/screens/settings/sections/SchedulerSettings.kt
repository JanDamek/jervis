package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ScheduledTaskDto
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.StatusIndicator
import kotlinx.coroutines.launch

@Composable
fun SchedulerSettings(repository: JervisRepository) {
    var tasks by remember { mutableStateOf<List<ScheduledTaskDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                tasks = repository.scheduledTasks.listAllTasks()
            } catch (e: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { loadData() }) {
                Text("⟳ Načíst")
            }
        }

        if (isLoading && tasks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks) { task ->
                    Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.taskName, style = MaterialTheme.typography.titleSmall)
                                task.cronExpression?.let {
                                    Text("Cron: $it", style = MaterialTheme.typography.labelSmall)
                                }
                                Text("Naplánováno: ${task.scheduledAt}", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            repository.scheduledTasks.cancelTask(task.id)
                                            loadData()
                                        } catch (e: Exception) {}
                                    }
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("🗑")
                            }
                        }
                    }
                }
            }
        }
    }
}
