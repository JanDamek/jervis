package com.jervis.ui.screens.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.PortMappingDto
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.componentTypeLabel

/**
 * Inline editor for a single environment component.
 * Shows all editable fields: name, type, image, ports, ENV vars, resource limits, health check, startup.
 */
@Composable
fun ComponentEditPanel(
    component: EnvironmentComponentDto,
    onSave: (EnvironmentComponentDto) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(component.name) }
    var type by remember { mutableStateOf(component.type) }
    var image by remember { mutableStateOf(component.image ?: "") }
    var cpuLimit by remember { mutableStateOf(component.cpuLimit ?: "") }
    var memoryLimit by remember { mutableStateOf(component.memoryLimit ?: "") }
    var healthCheckPath by remember { mutableStateOf(component.healthCheckPath ?: "") }
    var autoStart by remember { mutableStateOf(component.autoStart) }
    var startOrder by remember { mutableStateOf(component.startOrder.toString()) }
    var ports by remember { mutableStateOf(component.ports.toMutableList()) }
    var envVars by remember { mutableStateOf(component.envVars.entries.map { it.key to it.value }.toMutableList()) }

    Column(
        modifier = modifier.padding(JervisSpacing.sectionPadding),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
    ) {
        // Basic info
        JSection(title = "Základní") {
            JTextField(
                value = name,
                onValueChange = { name = it },
                label = "Název",
                singleLine = true,
            )
            Spacer(Modifier.height(JervisSpacing.fieldGap))
            JDropdown(
                items = ComponentTypeEnum.entries.toList(),
                selectedItem = type,
                onItemSelected = { type = it },
                label = "Typ",
                itemLabel = { componentTypeLabel(it) },
            )
            Spacer(Modifier.height(JervisSpacing.fieldGap))
            JTextField(
                value = image,
                onValueChange = { image = it },
                label = "Docker image (volitelné)",
                singleLine = true,
            )
        }

        // Ports
        JSection(title = "Porty") {
            ports.forEachIndexed { index, port ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
                ) {
                    JTextField(
                        value = port.containerPort.toString(),
                        onValueChange = { value ->
                            val portNum = value.toIntOrNull() ?: return@JTextField
                            ports = ports.toMutableList().also { it[index] = port.copy(containerPort = portNum) }
                        },
                        label = "Container",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    JTextField(
                        value = (port.servicePort ?: port.containerPort).toString(),
                        onValueChange = { value ->
                            val portNum = value.toIntOrNull() ?: return@JTextField
                            ports = ports.toMutableList().also { it[index] = port.copy(servicePort = portNum) }
                        },
                        label = "Service",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    JTextField(
                        value = port.name,
                        onValueChange = { value ->
                            ports = ports.toMutableList().also { it[index] = port.copy(name = value) }
                        },
                        label = "Název",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    JIconButton(
                        onClick = { ports = ports.toMutableList().also { it.removeAt(index) } },
                        icon = Icons.Default.Close,
                        contentDescription = "Odebrat port",
                    )
                }
            }
            Spacer(Modifier.height(JervisSpacing.fieldGap))
            JTextButton(onClick = {
                ports = (ports + PortMappingDto(containerPort = 8080)).toMutableList()
            }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Přidat port")
            }
        }

        // Environment variables
        JSection(title = "ENV proměnné") {
            envVars.forEachIndexed { index, (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
                ) {
                    JTextField(
                        value = key,
                        onValueChange = { newKey ->
                            envVars = envVars.toMutableList().also { it[index] = newKey to value }
                        },
                        label = "Klíč",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    JTextField(
                        value = value,
                        onValueChange = { newValue ->
                            envVars = envVars.toMutableList().also { it[index] = key to newValue }
                        },
                        label = "Hodnota",
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    JIconButton(
                        onClick = { envVars = envVars.toMutableList().also { it.removeAt(index) } },
                        icon = Icons.Default.Close,
                        contentDescription = "Odebrat",
                    )
                }
            }
            Spacer(Modifier.height(JervisSpacing.fieldGap))
            JTextButton(onClick = {
                envVars = (envVars + ("" to "")).toMutableList()
            }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Přidat proměnnou")
            }
        }

        // Resource limits
        JSection(title = "Resource limity") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                JTextField(
                    value = cpuLimit,
                    onValueChange = { cpuLimit = it },
                    label = "CPU limit (např. 500m, 1)",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                JTextField(
                    value = memoryLimit,
                    onValueChange = { memoryLimit = it },
                    label = "Memory limit (např. 512Mi, 2Gi)",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Health check
        JSection(title = "Health check") {
            JTextField(
                value = healthCheckPath,
                onValueChange = { healthCheckPath = it },
                label = "HTTP cesta (např. /health, /ready)",
                singleLine = true,
            )
        }

        // Startup
        JSection(title = "Spuštění") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                Text("Automatické spuštění", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = autoStart,
                    onCheckedChange = { autoStart = it },
                )
            }
            Spacer(Modifier.height(JervisSpacing.fieldGap))
            JTextField(
                value = startOrder,
                onValueChange = { startOrder = it },
                label = "Pořadí spuštění (0 = první)",
                singleLine = true,
            )
        }

        HorizontalDivider()

        // Save / Cancel actions
        JActionBar {
            JTextButton(onClick = onCancel) { Text("Zrušit") }
            JPrimaryButton(
                onClick = {
                    onSave(
                        component.copy(
                            name = name,
                            type = type,
                            image = image.ifBlank { null },
                            cpuLimit = cpuLimit.ifBlank { null },
                            memoryLimit = memoryLimit.ifBlank { null },
                            healthCheckPath = healthCheckPath.ifBlank { null },
                            autoStart = autoStart,
                            startOrder = startOrder.toIntOrNull() ?: 0,
                            ports = ports.filter { it.containerPort > 0 },
                            envVars = envVars
                                .filter { (k, _) -> k.isNotBlank() }
                                .associate { (k, v) -> k to v },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Uložit") }
        }
    }
}
