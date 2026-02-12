package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
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
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JRemoveIconButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog

@Composable
internal fun EnvironmentEditForm(
    environment: EnvironmentDto,
    repository: JervisRepository,
    onSave: (EnvironmentDto) -> Unit,
    onProvision: () -> Unit,
    onDeprovision: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(environment.name) }
    var description by remember { mutableStateOf(environment.description ?: "") }
    var namespace by remember { mutableStateOf(environment.namespace) }
    var agentInstructions by remember { mutableStateOf(environment.agentInstructions ?: "") }
    var components by remember { mutableStateOf(environment.components.toMutableList()) }
    var showAddComponentDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    JDetailScreen(
        title = environment.name,
        onBack = onCancel,
        onSave = {
            onSave(
                environment.copy(
                    name = name,
                    description = description.ifBlank { null },
                    namespace = namespace,
                    agentInstructions = agentInstructions.ifBlank { null },
                    components = components,
                ),
            )
        },
        saveEnabled = name.isNotBlank() && namespace.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()

        SelectionContainer {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Základní informace") {
                    JTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Název prostředí",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = namespace,
                        onValueChange = { namespace = it },
                        label = "K8s Namespace",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Popis",
                        singleLine = false,
                    )
                }

                JSection(title = "Komponenty") {
                    Text(
                        "Infrastrukturní komponenty (DB, cache) a projektové reference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (components.isEmpty()) {
                        Text(
                            "Žádné komponenty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        components.forEach { component ->
                            JCard {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            component.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        val typeLabel = componentTypeLabel(component.type)
                                        val imageInfo = component.image?.let { " · $it" } ?: ""
                                        Text(
                                            "$typeLabel$imageInfo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (component.ports.isNotEmpty()) {
                                            Text(
                                                component.ports.joinToString(", ") { "${it.containerPort}" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    JRemoveIconButton(
                                        onConfirmed = {
                                            components = components.filter { it.id != component.id }.toMutableList()
                                        },
                                        title = "Odebrat komponentu?",
                                        message = "Komponenta \"${component.name}\" bude odebrána z prostředí.",
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    JPrimaryButton(onClick = { showAddComponentDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Přidat komponentu")
                    }
                }

                JSection(title = "Instrukce pro agenta") {
                    JTextField(
                        value = agentInstructions,
                        onValueChange = { agentInstructions = it },
                        label = "Instrukce (volitelné)",
                        singleLine = false,
                    )
                    Text(
                        "Tyto instrukce budou předány coding agentovi jako kontext prostředí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Provisioning actions
                JSection(title = "Správa prostředí") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (environment.state == EnvironmentStateEnum.PENDING ||
                            environment.state == EnvironmentStateEnum.STOPPED ||
                            environment.state == EnvironmentStateEnum.ERROR
                        ) {
                            JPrimaryButton(onClick = onProvision) {
                                Text("Provisionovat")
                            }
                        }
                        if (environment.state == EnvironmentStateEnum.RUNNING) {
                            JSecondaryButton(onClick = onDeprovision) {
                                Text("Zastavit")
                            }
                        }
                    }
                }

                JSection(title = "Nebezpečná zóna") {
                    JDestructiveButton(onClick = { showDeleteConfirm = true }) {
                        Text("Smazat prostředí")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    ConfirmDialog(
        visible = showDeleteConfirm,
        title = "Smazat prostředí?",
        message = "Tato akce je nevratná. Pokud je prostředí provisionované, bude nejdříve zastaveno.",
        confirmText = "Smazat",
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )

    if (showAddComponentDialog) {
        AddComponentDialog(
            existingComponents = components,
            onAdd = { newComponent ->
                components = (components + newComponent).toMutableList()
            },
            onDismiss = { showAddComponentDialog = false },
        )
    }
}
