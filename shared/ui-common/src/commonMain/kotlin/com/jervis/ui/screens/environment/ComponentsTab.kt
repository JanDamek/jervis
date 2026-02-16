package com.jervis.ui.screens.environment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JRemoveIconButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.AddComponentDialog
import com.jervis.ui.screens.settings.sections.componentTypeLabel
import kotlinx.coroutines.launch

/**
 * Components tab for Environment Manager.
 * Shows a list of components as expandable JCards with inline editing.
 */
@Composable
fun ComponentsTab(
    environment: EnvironmentDto,
    repository: JervisRepository,
    onUpdated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedComponentId by remember { mutableStateOf<String?>(null) }
    var editingComponentId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun saveEnvironment(updatedEnv: EnvironmentDto) {
        scope.launch {
            try {
                repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
                onUpdated()
            } catch (_: Exception) {}
        }
    }

    Column(modifier = modifier.padding(vertical = JervisSpacing.outerPadding)) {
        // Header with add button
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = JervisSpacing.itemGap),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${environment.components.size} komponent",
                style = MaterialTheme.typography.titleMedium,
            )
            JPrimaryButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Přidat")
            }
        }

        if (environment.components.isEmpty()) {
            JEmptyState(
                message = "Žádné komponenty. Přidejte infrastrukturní služby nebo projektové reference.",
                icon = "\uD83D\uDCE6",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                items(environment.components, key = { it.id }) { component ->
                    val isExpanded = expandedComponentId == component.id
                    val isEditing = editingComponentId == component.id

                    ComponentCard(
                        component = component,
                        isExpanded = isExpanded,
                        isEditing = isEditing,
                        onToggleExpand = {
                            expandedComponentId = if (isExpanded) null else component.id
                            if (isExpanded) editingComponentId = null
                        },
                        onEdit = {
                            editingComponentId = component.id
                            expandedComponentId = component.id
                        },
                        onSave = { updated ->
                            val updatedComponents = environment.components.map {
                                if (it.id == component.id) updated else it
                            }
                            saveEnvironment(environment.copy(components = updatedComponents))
                            editingComponentId = null
                        },
                        onCancelEdit = { editingComponentId = null },
                        onRemove = {
                            val updatedComponents = environment.components.filter { it.id != component.id }
                            saveEnvironment(environment.copy(components = updatedComponents))
                        },
                    )
                }
            }
        }
    }

    // Add component dialog
    if (showAddDialog) {
        AddComponentDialog(
            existingComponents = environment.components,
            onAdd = { newComponent ->
                val updatedComponents = environment.components + newComponent
                saveEnvironment(environment.copy(components = updatedComponents))
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

/**
 * Expandable card for a single component.
 * Collapsed: shows name, type, image summary, ports, ENV count.
 * Expanded: shows detail info or inline editor.
 */
@Composable
private fun ComponentCard(
    component: EnvironmentComponentDto,
    isExpanded: Boolean,
    isEditing: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onSave: (EnvironmentComponentDto) -> Unit,
    onCancelEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    JCard(onClick = onToggleExpand) {
        // Header row (always visible)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Sbalit" else "Rozbalit",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        componentTypeLabel(component.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(component.name, style = MaterialTheme.typography.titleSmall)
                }
                // Summary line
                val summaryParts = buildList {
                    component.image?.let { add(it.substringAfterLast("/")) }
                    if (component.ports.isNotEmpty()) {
                        add("porty: ${component.ports.joinToString(",") { "${it.containerPort}" }}")
                    }
                    if (component.envVars.isNotEmpty()) {
                        add("${component.envVars.size} ENV")
                    }
                }
                if (summaryParts.isNotEmpty()) {
                    Text(
                        summaryParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            JRemoveIconButton(
                onConfirmed = onRemove,
                title = "Odebrat komponentu?",
                message = "Komponenta \"${component.name}\" bude odebrána z prostředí.",
            )
        }

        // Expanded content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = JervisSpacing.itemGap)) {
                if (isEditing) {
                    ComponentEditPanel(
                        component = component,
                        onSave = onSave,
                        onCancel = onCancelEdit,
                    )
                } else {
                    // Read-only detail + Edit button
                    ComponentReadOnlyDetail(
                        component = component,
                        onEdit = onEdit,
                    )
                }
            }
        }
    }
}

/**
 * Read-only detail view of a component (shown when expanded but not editing).
 */
@Composable
private fun ComponentReadOnlyDetail(
    component: EnvironmentComponentDto,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 28.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        component.image?.let {
            com.jervis.ui.design.JKeyValueRow("Image", it)
        }
        if (component.ports.isNotEmpty()) {
            com.jervis.ui.design.JKeyValueRow(
                "Porty",
                component.ports.joinToString(", ") {
                    "${it.containerPort}${if (it.servicePort != null && it.servicePort != it.containerPort) "→${it.servicePort}" else ""}${if (it.name.isNotBlank()) " (${it.name})" else ""}"
                },
            )
        }
        if (component.envVars.isNotEmpty()) {
            com.jervis.ui.design.JKeyValueRow("ENV proměnné", "${component.envVars.size} definováno")
        }
        component.cpuLimit?.let { com.jervis.ui.design.JKeyValueRow("CPU limit", it) }
        component.memoryLimit?.let { com.jervis.ui.design.JKeyValueRow("Memory limit", it) }
        component.healthCheckPath?.let { com.jervis.ui.design.JKeyValueRow("Health check", it) }
        com.jervis.ui.design.JKeyValueRow("Auto start", if (component.autoStart) "Ano" else "Ne")
        if (component.startOrder != 0) {
            com.jervis.ui.design.JKeyValueRow("Pořadí", "${component.startOrder}")
        }

        Spacer(Modifier.height(JervisSpacing.fieldGap))
        com.jervis.ui.design.JTextButton(onClick = onEdit) {
            Text("Upravit")
        }
    }
}
