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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.dto.environment.EnvironmentTierEnum
import com.jervis.ui.screens.settings.sections.environmentTierLabel
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.environment.EnvironmentStateBadge

/**
 * Overview tab for Environment Manager — editable fields + read-only summary + actions.
 *
 * Editable: name, description, tier, namespace (only PENDING/STOPPED/ERROR),
 * storageSizeGi, agentInstructions.
 * Read-only: clientId, groupId, projectId, state, components summary, property mappings.
 */
@Composable
fun OverviewTab(
    environment: EnvironmentDto,
    status: EnvironmentStatusDto?,
    onProvision: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit = {},
    onSave: (EnvironmentDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentState = status?.state ?: environment.state
    val canEditNamespace = currentState in listOf(
        EnvironmentStateEnum.PENDING,
        EnvironmentStateEnum.STOPPED,
        EnvironmentStateEnum.ERROR,
    )

    // Editable state — keyed on environment.id to reset when switching environments
    var name by remember(environment.id) { mutableStateOf(environment.name) }
    var description by remember(environment.id) { mutableStateOf(environment.description ?: "") }
    var tier by remember(environment.id) { mutableStateOf(environment.tier) }
    var namespace by remember(environment.id) { mutableStateOf(environment.namespace) }
    var storageSizeGi by remember(environment.id) { mutableStateOf(environment.storageSizeGi.toString()) }
    var agentInstructions by remember(environment.id) { mutableStateOf(environment.agentInstructions ?: "") }

    val hasChanges = name != environment.name ||
        description != (environment.description ?: "") ||
        tier != environment.tier ||
        namespace != environment.namespace ||
        storageSizeGi != environment.storageSizeGi.toString() ||
        agentInstructions != (environment.agentInstructions ?: "")

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = JervisSpacing.outerPadding),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
    ) {
        // Basic info section — editable fields
        JSection(title = "Základní informace") {
            JTextField(
                value = name,
                onValueChange = { name = it },
                label = "Název",
            )
            JDropdown(
                items = EnvironmentTierEnum.entries.toList(),
                selectedItem = tier,
                onItemSelected = { tier = it },
                label = "Typ prostředí",
                itemLabel = { environmentTierLabel(it) },
            )
            JTextField(
                value = namespace,
                onValueChange = { namespace = it },
                label = "Namespace",
                enabled = canEditNamespace,
            )
            JTextField(
                value = description,
                onValueChange = { description = it },
                label = "Popis",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                placeholder = "Volitelný popis prostředí",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stav:", style = MaterialTheme.typography.bodyMedium)
                EnvironmentStateBadge(currentState)
            }
        }

        // Assignment section — read-only
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

        // Components summary — read-only
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

        // Property mappings summary — read-only
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

        // Storage section — editable size
        JSection(title = "Úložiště") {
            JKeyValueRow("Strategie", "Jeden PVC na prostředí")
            JTextField(
                value = storageSizeGi,
                onValueChange = { newVal -> storageSizeGi = newVal.filter { it.isDigit() } },
                label = "Velikost (Gi)",
            )
            JKeyValueRow("Název PVC", "env-data-$namespace")

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

        // Agent instructions — editable
        JSection(title = "Pokyny pro agenta") {
            JTextField(
                value = agentInstructions,
                onValueChange = { agentInstructions = it },
                label = "Instrukce pro AI agenta",
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                placeholder = "Volitelné pokyny pro agenta při práci s tímto prostředím",
            )
        }

        // Save button — visible only when changes are detected
        if (hasChanges) {
            JPrimaryButton(
                onClick = {
                    val updated = environment.copy(
                        name = name,
                        description = description.ifBlank { null },
                        tier = tier,
                        namespace = namespace,
                        storageSizeGi = storageSizeGi.toIntOrNull() ?: environment.storageSizeGi,
                        agentInstructions = agentInstructions.ifBlank { null },
                    )
                    onSave(updated)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Uložit změny")
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
