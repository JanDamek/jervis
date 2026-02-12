package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jervis.dto.ProjectDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
fun ProjectsSettings(repository: JervisRepository) {
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                projects = repository.projects.getAllProjects()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    JListDetailLayout(
        items = projects,
        selectedItem = selectedProject,
        isLoading = isLoading,
        onItemSelected = { selectedProject = it },
        emptyMessage = "Žádné projekty nenalezeny",
        emptyIcon = "📁",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
            }
        },
        listItem = { project ->
            JCard(
                onClick = { selectedProject = project },
            ) {
                Row(
                    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            project.description ?: "Bez popisu",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (project.resources.isNotEmpty()) {
                            val summary = project.resources.groupBy { it.capability }
                                .entries.joinToString(", ") { (cap, res) ->
                                    "${res.size}x ${getCapabilityLabel(cap)}"
                                }
                            Text(
                                summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        detailContent = { project ->
            ProjectEditForm(
                project = project,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.projects.updateProject(updated.id ?: "", updated)
                            selectedProject = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onCancel = { selectedProject = null },
            )
        },
    )
}
