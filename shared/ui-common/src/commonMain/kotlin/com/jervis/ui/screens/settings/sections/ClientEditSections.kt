package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JRemoveIconButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

@Composable
internal fun ClientConnectionsSection(
    selectedConnectionIds: MutableSet<String>,
    availableConnections: List<ConnectionResponseDto>,
    onConnectionsChanged: () -> Unit,
) {
    var showConnectionsDialog by remember { mutableStateOf(false) }

    JSection(title = "Připojení klienta") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Přiřaďte connections tomuto klientovi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            JPrimaryButton(onClick = { showConnectionsDialog = true }) {
                Text("+ Přidat")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (selectedConnectionIds.isEmpty()) {
            Text(
                "Žádná připojení nejsou přiřazena.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            selectedConnectionIds.forEach { connId ->
                val connection = availableConnections.firstOrNull { it.id == connId }
                JCard {
                    Row(
                        modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                connection?.name ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                connection?.protocol?.name ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        JRemoveIconButton(
                            onConfirmed = {
                                selectedConnectionIds.remove(connId)
                                onConnectionsChanged()
                            },
                            title = "Odebrat připojení?",
                            message = "Připojení \"${connection?.name ?: ""}\" bude odebráno od klienta.",
                        )
                    }
                }
            }
        }
    }

    if (showConnectionsDialog) {
        AlertDialog(
            onDismissRequest = { showConnectionsDialog = false },
            title = { Text("Vybrat připojení") },
            text = {
                LazyColumn {
                    items(availableConnections.filter { it.id !in selectedConnectionIds }) { conn ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedConnectionIds.add(conn.id)
                                    showConnectionsDialog = false
                                    onConnectionsChanged()
                                }
                                .padding(12.dp)
                                .heightIn(min = JervisSpacing.touchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conn.name, style = MaterialTheme.typography.bodyMedium)
                                Text(conn.protocol.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                JTextButton(onClick = { showConnectionsDialog = false }) {
                    Text("Zavřít")
                }
            },
        )
    }
}

@Composable
internal fun ClientProjectsSection(
    clientId: String,
    projects: List<ProjectDto>,
    repository: JervisRepository,
    onProjectsChanged: () -> Unit,
) {
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    JSection(title = "Projekty klienta") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Projekty přiřazené tomuto klientovi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            JPrimaryButton(onClick = { showCreateProjectDialog = true }) {
                Text("+ Vytvořit projekt")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (projects.isEmpty()) {
            Text(
                "Žádné projekty nejsou vytvořeny.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            projects.forEach { project ->
                JCard(
                    onClick = {
                        // TODO: Navigate to project detail
                    },
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                project.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            project.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            }
        }
    }

    if (showCreateProjectDialog) {
        var newProjectName by remember { mutableStateOf("") }
        var newProjectDescription by remember { mutableStateOf("") }

        JFormDialog(
            visible = true,
            title = "Vytvořit nový projekt",
            onConfirm = {
                scope.launch {
                    try {
                        repository.projects.saveProject(
                            ProjectDto(
                                name = newProjectName,
                                description = newProjectDescription.ifBlank { null },
                                clientId = clientId,
                            ),
                        )
                        onProjectsChanged()
                        showCreateProjectDialog = false
                    } catch (_: Exception) {
                    }
                }
            },
            onDismiss = { showCreateProjectDialog = false },
            confirmEnabled = newProjectName.isNotBlank(),
            confirmText = "Vytvořit",
        ) {
            JTextField(
                value = newProjectName,
                onValueChange = { newProjectName = it },
                label = "Název projektu",
            )
            JTextField(
                value = newProjectDescription,
                onValueChange = { newProjectDescription = it },
                label = "Popis (volitelné)",
                singleLine = false,
                minLines = 2,
            )
        }
    }
}
