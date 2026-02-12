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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing

internal data class ResourceSelection(
    val connectionId: String,
    val capability: ConnectionCapability,
    val resourceId: String,
    val displayName: String,
)

@Composable
internal fun AddResourceDialog(
    clientConnections: List<ConnectionResponseDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    existingResources: List<ProjectResourceDto>,
    capabilityFilter: ConnectionCapability?,
    onAdd: (connectionId: String, capability: ConnectionCapability, resourceId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Set<ResourceSelection>>(emptySet()) }
    var filterText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat zdroje") },
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
                        val caps = if (capabilityFilter != null) {
                            connection.capabilities.filter { it == capabilityFilter }
                        } else {
                            connection.capabilities
                        }

                        caps.forEach { capability ->
                            val key = Pair(connection.id, capability)
                            val allResources = availableResources[key] ?: emptyList()
                            val isLoading = key in loadingResources
                            val notYetLoaded = key !in availableResources

                            val filteredResources = allResources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    filterText.isBlank() ||
                                        res.name.contains(filterText, ignoreCase = true) ||
                                        res.id.contains(filterText, ignoreCase = true) ||
                                        (res.description?.contains(filterText, ignoreCase = true) == true)
                                }

                            if (isLoading || notYetLoaded) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .heightIn(min = JervisSpacing.touchTarget),
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
                                    val sel = ResourceSelection(connection.id, capability, resource.id, resource.name)
                                    val isSelected = sel in selected

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !alreadyAdded) {
                                                selected = if (isSelected) selected - sel else selected + sel
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .heightIn(min = JervisSpacing.touchTarget),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = alreadyAdded || isSelected,
                                            onCheckedChange = if (alreadyAdded) null else { _ ->
                                                selected = if (isSelected) selected - sel else selected + sel
                                            },
                                            enabled = !alreadyAdded,
                                        )
                                        Spacer(Modifier.width(8.dp))
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
                                                resource.id + (resource.description?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = if (alreadyAdded) 0.3f else 0.7f,
                                                ),
                                                maxLines = 1,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JTextButton(onClick = onDismiss) {
                    Text("Zavřít")
                }
                if (selected.isNotEmpty()) {
                    JPrimaryButton(onClick = {
                        selected.forEach { sel ->
                            onAdd(sel.connectionId, sel.capability, sel.resourceId, sel.displayName)
                        }
                        onDismiss()
                    }) {
                        Text("Přidat vybrané (${selected.size})")
                    }
                }
            }
        },
    )
}

@Composable
internal fun LinkResourceDialog(
    sourceResource: ProjectResourceDto,
    allResources: List<ProjectResourceDto>,
    existingLinks: List<ResourceLinkDto>,
    onLink: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val linkableResources = allResources.filter { target ->
        target.id != sourceResource.id &&
            target.id.isNotEmpty() &&
            target.capability != sourceResource.capability &&
            !existingLinks.any {
                (it.sourceId == sourceResource.id && it.targetId == target.id) ||
                    (it.sourceId == target.id && it.targetId == sourceResource.id)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Propojit: ${sourceResource.displayName.ifEmpty { sourceResource.resourceIdentifier }}")
        },
        text = {
            if (linkableResources.isEmpty()) {
                Text(
                    "Žádné dostupné zdroje pro propojení. Přidejte zdroje jiného typu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    val grouped = linkableResources.groupBy { it.capability }
                    grouped.forEach { (capability, capResources) ->
                        item {
                            Text(
                                getCapabilityLabel(capability),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(capResources) { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLink(target.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .heightIn(min = JervisSpacing.touchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        target.displayName.ifEmpty { target.resourceIdentifier },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        target.resourceIdentifier,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
