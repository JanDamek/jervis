package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ProjectDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.components.StatusIndicator

@Composable
fun ProjectsSettings(repository: JervisRepository) {
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            projects = repository.projects.getAllProjects()
        } catch (e: Exception) {
            // Error handling
        } finally {
            isLoading = false
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(projects) { project ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(project.description ?: "Bez popisu", style = MaterialTheme.typography.bodySmall)
                    }
                    StatusIndicator("ACTIVE")
                }
            }
        }
    }
}
