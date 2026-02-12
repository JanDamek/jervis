package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import com.jervis.dto.ProjectGroupDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
fun ProjectGroupsSettings(repository: JervisRepository) {
    var groups by remember { mutableStateOf<List<ProjectGroupDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<ProjectGroupDto?>(null) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                groups = repository.projectGroups.getAllGroups()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    JListDetailLayout(
        items = groups,
        selectedItem = selectedGroup,
        isLoading = isLoading,
        onItemSelected = { selectedGroup = it },
        emptyMessage = "Žádné skupiny projektů",
        emptyIcon = "📂",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = { showNewGroupDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nová skupina")
                }
            }
        },
        listItem = { group ->
            JCard(
                onClick = { selectedGroup = group },
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            group.description ?: "Bez popisu",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (group.resources.isNotEmpty()) {
                            val summary = group.resources.groupBy { it.capability }
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
        detailContent = { group ->
            ProjectGroupEditForm(
                group = group,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.projectGroups.updateGroup(updated.id, updated)
                            selectedGroup = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        try {
                            repository.projectGroups.deleteGroup(group.id)
                            selectedGroup = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onCancel = { selectedGroup = null },
            )
        },
    )

    if (showNewGroupDialog) {
        NewProjectGroupDialog(
            repository = repository,
            onCreated = {
                showNewGroupDialog = false
                loadData()
            },
            onDismiss = { showNewGroupDialog = false },
        )
    }
}
