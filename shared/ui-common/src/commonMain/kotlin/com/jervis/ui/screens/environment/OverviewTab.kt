package com.jervis.ui.screens.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.environment.EnvironmentStateBadge

/**
 * Overview tab for Environment Manager — read-only summary + action buttons.
 */
@Composable
fun OverviewTab(
    environment: EnvironmentDto,
    status: EnvironmentStatusDto?,
    onProvision: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentState = status?.state ?: environment.state

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = JervisSpacing.outerPadding),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
    ) {
        // Basic info section
        JSection(title = "Základní informace") {
            JKeyValueRow("Název", environment.name)
            JKeyValueRow("Namespace", environment.namespace)
            environment.description?.let { desc ->
                if (desc.isNotBlank()) {
                    JKeyValueRow("Popis", desc)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stav:", style = MaterialTheme.typography.bodyMedium)
                EnvironmentStateBadge(currentState)
            }
        }

        // Assignment section
        JSection(title = "Přiřazení") {
            JKeyValueRow("Klient ID", environment.clientId)
            environment.groupId?.let { JKeyValueRow("Skupina ID", it) }
            environment.projectId?.let { JKeyValueRow("Projekt ID", it) }
            if (environment.groupId == null && environment.projectId == null) {
                Text(
                    "Celý klient (všechny projekty)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Components summary
        JSection(title = "Komponenty") {
            val infraComponents = environment.components.filter { it.type != ComponentTypeEnum.PROJECT }
            val projectComponents = environment.components.filter { it.type == ComponentTypeEnum.PROJECT }

            JKeyValueRow("Celkem", "${environment.components.size}")
            if (infraComponents.isNotEmpty()) {
                JKeyValueRow("Infrastruktura", "${infraComponents.size}")
            }
            if (projectComponents.isNotEmpty()) {
                JKeyValueRow("Projekty", "${projectComponents.size}")
            }
            if (environment.components.isEmpty()) {
                Text(
                    "Žádné komponenty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Property mappings summary
        if (environment.propertyMappings.isNotEmpty()) {
            JSection(title = "Mapování vlastností") {
                JKeyValueRow("Celkem mapování", "${environment.propertyMappings.size}")
                environment.propertyMappings.take(5).forEach { mapping ->
                    val target = environment.components.find { it.id == mapping.targetComponentId }
                    JKeyValueRow(
                        mapping.propertyName,
                        if (mapping.resolvedValue != null) mapping.resolvedValue!!
                        else "${mapping.valueTemplate} \u2192 ${target?.name ?: "?"}",
                    )
                }
                if (environment.propertyMappings.size > 5) {
                    Text(
                        "... a ${environment.propertyMappings.size - 5} dalších",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Storage section
        JSection(title = "Úložiště") {
            JKeyValueRow("Strategie", "Jeden PVC na prostředí")
            JKeyValueRow("Velikost", "${environment.storageSizeGi} Gi")
            JKeyValueRow("Název PVC", "jervis-env-data")

            val componentsWithStorage = environment.components.filter {
                it.type != ComponentTypeEnum.PROJECT && it.volumeMountPath != null
            }
            if (componentsWithStorage.isNotEmpty()) {
                JKeyValueRow(
                    "Komponenty s úložištěm",
                    componentsWithStorage.joinToString(", ") { it.name },
                )
            }
        }

        // Agent instructions (if any)
        environment.agentInstructions?.let { instructions ->
            if (instructions.isNotBlank()) {
                JSection(title = "Pokyny pro agenta") {
                    Text(
                        instructions,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Action buttons
        Spacer(Modifier.height(JervisSpacing.sectionGap))
        JActionBar {
            when (currentState) {
                EnvironmentStateEnum.PENDING, EnvironmentStateEnum.STOPPED -> {
                    JTextButton(onClick = onDelete) { Text("Smazat") }
                    JPrimaryButton(onClick = onProvision) { Text("Provisionovat") }
                }
                EnvironmentStateEnum.RUNNING -> {
                    JTextButton(onClick = onDelete) { Text("Smazat") }
                    JSecondaryButton(onClick = onSync) { Text("Synchronizovat") }
                    JPrimaryButton(onClick = onStop) { Text("Zastavit") }
                }
                EnvironmentStateEnum.CREATING, EnvironmentStateEnum.STOPPING -> {
                    Text(
                        "Probíhá operace...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EnvironmentStateEnum.ERROR -> {
                    JTextButton(onClick = onDelete) { Text("Smazat") }
                    JPrimaryButton(onClick = onProvision) { Text("Znovu provisionovat") }
                }
            }
        }
    }
}
