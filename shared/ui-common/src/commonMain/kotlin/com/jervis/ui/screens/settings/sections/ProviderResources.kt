package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

@Composable
internal fun ProviderResourcesCard(
    connection: ConnectionResponseDto,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    findLinkedProject: (ConnectionCapability, String) -> ProjectDto?,
    onCreateProject: (ConnectionResourceDto, ConnectionCapability) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    val totalResources = connection.capabilities.sumOf { cap ->
        availableResources[Pair(connection.id, cap)]?.size ?: 0
    }
    val isAnyLoading = connection.capabilities.any { cap ->
        Pair(connection.id, cap) in loadingResources
    }

    JCard(
        onClick = { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    connection.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${connection.provider.name} · $totalResources zdrojů",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isAnyLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            var providerResourceFilter by remember { mutableStateOf("") }
            val allResourceCount = connection.capabilities.sumOf { cap ->
                (availableResources[Pair(connection.id, cap)] ?: emptyList()).size
            }

            if (allResourceCount > 5) {
                Spacer(Modifier.height(8.dp))
                JTextField(
                    value = providerResourceFilter,
                    onValueChange = { providerResourceFilter = it },
                    label = "Filtrovat zdroje...",
                    singleLine = true,
                )
            }

            connection.capabilities.forEach { capability ->
                val key = Pair(connection.id, capability)
                val resources = availableResources[key] ?: emptyList()
                val isLoading = key in loadingResources

                val sortedFiltered = resources
                    .sortedBy { it.name.lowercase() }
                    .filter { res ->
                        providerResourceFilter.isBlank() ||
                            res.name.contains(providerResourceFilter, ignoreCase = true) ||
                            res.id.contains(providerResourceFilter, ignoreCase = true) ||
                            (res.description?.contains(providerResourceFilter, ignoreCase = true) == true)
                    }

                if (isLoading && resources.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Načítám ${getCapabilityLabel(capability)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (sortedFiltered.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        getCapabilityLabel(capability),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))

                    sortedFiltered.forEach { resource ->
                        val linkedProject = findLinkedProject(capability, resource.id)
                        ProviderResourceRow(
                            resource = resource,
                            linkedProject = linkedProject,
                            onCreateProject = { onCreateProject(resource, capability) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderResourceRow(
    resource: ConnectionResourceDto,
    linkedProject: ProjectDto?,
    onCreateProject: () -> Unit,
) {
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(linkedProject) {
        if (linkedProject != null) isCreating = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .heightIn(min = JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(resource.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                resource.id + (resource.description?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (linkedProject != null) {
            Text(
                linkedProject.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            JPrimaryButton(
                onClick = {
                    isCreating = true
                    onCreateProject()
                },
                enabled = !isCreating,
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Vytvořit", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
