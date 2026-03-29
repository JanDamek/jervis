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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.jervis.dto.client.ClientConnectionCapabilityDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

@Composable
internal fun ConnectionCapabilityCard(
    connection: ConnectionResponseDto,
    capabilities: List<ClientConnectionCapabilityDto>,
    availableResources: Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>,
    loadingResources: Set<Pair<String, ConnectionCapability>>,
    errorResources: Set<Pair<String, ConnectionCapability>> = emptySet(),
    onLoadResources: (ConnectionCapability) -> Unit,
    onRetryResources: (ConnectionCapability) -> Unit = onLoadResources,
    onUpdateConfig: (ClientConnectionCapabilityDto) -> Unit,
    onRemoveConfig: (ConnectionCapability) -> Unit,
    getConfig: (ConnectionCapability) -> ClientConnectionCapabilityDto?,
) {
    var expanded by remember { mutableStateOf(false) }

    JCard(
        onClick = { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    connection.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (connection.state == ConnectionStateEnum.DISCOVERING) {
                        "Zjišťuji dostupné služby..."
                    } else {
                        connection.capabilities.joinToString(", ") { it.name }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            if (connection.state == ConnectionStateEnum.DISCOVERING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Zjišťuji dostupné služby — konfigurace bude možná po dokončení...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                connection.capabilities.forEach { capability ->
                    CapabilityConfigItem(
                        connectionId = connection.id,
                        capability = capability,
                        config = getConfig(capability),
                        resources = availableResources[Pair(connection.id, capability)] ?: emptyList(),
                        isLoadingResources = Pair(connection.id, capability) in loadingResources,
                        hasError = Pair(connection.id, capability) in errorResources,
                        onLoadResources = { onLoadResources(capability) },
                        onRetryResources = { onRetryResources(capability) },
                        onUpdateConfig = onUpdateConfig,
                        onRemoveConfig = { onRemoveConfig(capability) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CapabilityConfigItem(
    connectionId: String,
    capability: ConnectionCapability,
    config: ClientConnectionCapabilityDto?,
    resources: List<ConnectionResourceDto>,
    isLoadingResources: Boolean,
    hasError: Boolean = false,
    onLoadResources: () -> Unit,
    onRetryResources: () -> Unit = onLoadResources,
    onUpdateConfig: (ClientConnectionCapabilityDto) -> Unit,
    onRemoveConfig: () -> Unit,
) {
    val isEnabled = config?.enabled ?: false
    val indexAllResources = config?.indexAllResources ?: true
    val selectedResources = config?.selectedResources ?: emptyList()

    LaunchedEffect(isEnabled, indexAllResources) {
        if (isEnabled && !indexAllResources && resources.isEmpty()) {
            onLoadResources()
        }
    }

    Column {
        JCheckboxRow(
            label = getCapabilityLabel(capability),
            checked = isEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    onUpdateConfig(
                        ClientConnectionCapabilityDto(
                            connectionId = connectionId,
                            capability = capability,
                            enabled = true,
                            indexAllResources = true,
                            selectedResources = emptyList(),
                        ),
                    )
                } else {
                    onRemoveConfig()
                }
            },
        )

        if (isEnabled) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = JervisSpacing.touchTarget)
                        .clickable {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = true,
                                    selectedResources = emptyList(),
                                ),
                            )
                        },
                ) {
                    RadioButton(
                        selected = indexAllResources,
                        onClick = {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = true,
                                    selectedResources = emptyList(),
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        getIndexAllLabel(capability),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = JervisSpacing.touchTarget)
                        .clickable {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = false,
                                    selectedResources = selectedResources,
                                ),
                            )
                            onLoadResources()
                        },
                ) {
                    RadioButton(
                        selected = !indexAllResources,
                        onClick = {
                            onUpdateConfig(
                                ClientConnectionCapabilityDto(
                                    connectionId = connectionId,
                                    capability = capability,
                                    enabled = true,
                                    indexAllResources = false,
                                    selectedResources = selectedResources,
                                ),
                            )
                            onLoadResources()
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Pouze vybrané:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!indexAllResources) {
                    var resourceFilter by remember { mutableStateOf("") }

                    Column(modifier = Modifier.padding(start = 32.dp, top = 4.dp)) {
                        if (isLoadingResources) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Načítám dostupné zdroje...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (hasError && resources.isEmpty()) {
                            Text(
                                "Chyba načítání zdrojů — zkontrolujte připojení a token.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Zkusit znovu",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onRetryResources() }
                                    .padding(vertical = 4.dp),
                            )
                        } else if (resources.isEmpty()) {
                            Text(
                                "Žádné zdroje k dispozici.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (resources.size > 5) {
                                JTextField(
                                    value = resourceFilter,
                                    onValueChange = { resourceFilter = it },
                                    label = "Filtrovat...",
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(4.dp))
                            }

                            val sortedFiltered = resources
                                .sortedBy { it.name.lowercase() }
                                .filter { res ->
                                    resourceFilter.isBlank() ||
                                        res.name.contains(resourceFilter, ignoreCase = true) ||
                                        res.id.contains(resourceFilter, ignoreCase = true) ||
                                        (res.description?.contains(resourceFilter, ignoreCase = true) == true)
                                }

                            sortedFiltered.forEach { resource ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                ) {
                                    Checkbox(
                                        checked = resource.id in selectedResources,
                                        onCheckedChange = { checked ->
                                            val newSelected = if (checked) {
                                                selectedResources + resource.id
                                            } else {
                                                selectedResources - resource.id
                                            }
                                            onUpdateConfig(
                                                ClientConnectionCapabilityDto(
                                                    connectionId = connectionId,
                                                    capability = capability,
                                                    enabled = true,
                                                    indexAllResources = false,
                                                    selectedResources = newSelected,
                                                ),
                                            )
                                        },
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            resource.name,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        resource.description?.let { desc ->
                                            Text(
                                                desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
