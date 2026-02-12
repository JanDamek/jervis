package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectGroupDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

@Composable
internal fun AddGroupResourceDialog(
    clientConnections: List<ConnectionResponseDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    existingResources: List<ProjectResourceDto>,
    onAdd: (connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filterText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat sdílený zdroj") },
        text = {
            Column {
                JTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = "Filtrovat...",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    clientConnections.forEach { connection ->
                        connection.capabilities.forEach { capability ->
                            val key = Pair(connection.id, capability)
                            val allResources = availableResources[key] ?: emptyList()
                            val isLoading = key in loadingResources

                            val filteredResources = allResources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    filterText.isBlank() ||
                                        res.name.contains(filterText, ignoreCase = true) ||
                                        res.id.contains(filterText, ignoreCase = true)
                                }

                            if (isLoading) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp).heightIn(min = JervisSpacing.touchTarget),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${connection.name} · ${getCapabilityLabel(capability)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            } else if (filteredResources.isNotEmpty()) {
                                item {
                                    Text(
                                        "${connection.name} · ${getCapabilityLabel(capability)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(filteredResources) { resource ->
                                    val alreadyAdded = existingResources.any {
                                        it.connectionId == connection.id &&
                                            it.capability == capability &&
                                            it.resourceIdentifier == resource.id
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !alreadyAdded) {
                                                onAdd(connection.id, capability, resource.id, resource.name)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .heightIn(min = JervisSpacing.touchTarget),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                resource.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (alreadyAdded) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            Text(
                                                resource.id,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (alreadyAdded) {
                                            Text(
                                                "Přidáno",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            JTextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        },
    )
}

@Composable
internal fun NewProjectGroupDialog(
    repository: JervisRepository,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.getAllClients()
            if (clients.size == 1) {
                selectedClientId = clients.first().id
            }
        } catch (_: Exception) {
        }
    }

    JFormDialog(
        visible = true,
        title = "Nová skupina projektů",
        onConfirm = {
            val clientId = selectedClientId ?: return@JFormDialog
            isSaving = true
            scope.launch {
                try {
                    repository.projectGroups.saveGroup(
                        ProjectGroupDto(
                            clientId = clientId,
                            name = name,
                            description = description.ifBlank { null },
                        ),
                    )
                    onCreated()
                } catch (_: Exception) {
                    isSaving = false
                }
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank() && selectedClientId != null && !isSaving,
        confirmText = "Vytvořit",
    ) {
        JTextField(
            value = name,
            onValueChange = { name = it },
            label = "Název skupiny",
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        JTextField(
            value = description,
            onValueChange = { description = it },
            label = "Popis (volitelné)",
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = clients,
            selectedItem = clients.find { it.id == selectedClientId },
            onItemSelected = { selectedClientId = it.id },
            label = "Klient",
            itemLabel = { it.name },
        )
    }
}
