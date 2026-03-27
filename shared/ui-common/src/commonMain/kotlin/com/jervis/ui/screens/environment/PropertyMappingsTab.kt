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
import androidx.compose.material.icons.filled.AutoFixHigh
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.ComponentTemplateDto
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.PropertyMappingDto
import com.jervis.di.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JRemoveIconButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.screens.settings.sections.componentTypeLabel
import kotlinx.coroutines.launch

/**
 * Property Mappings tab — manage how infrastructure connection details
 * (URL, credentials, ports) are automatically passed to project components as ENV vars.
 *
 * Shows existing mappings with edit/remove, and auto-suggest from templates
 * when PROJECT components are linked to infrastructure components.
 */
@Composable
fun PropertyMappingsTab(
    environment: EnvironmentDto,
    repository: JervisRepository,
    templates: List<ComponentTemplateDto> = emptyList(),
    onUpdated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedMappingIndex by remember { mutableStateOf<Int?>(null) }
    var showAddForm by remember { mutableStateOf(false) }
    var showAutoSuggest by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val projectComponents = environment.components.filter { it.type == ComponentTypeEnum.PROJECT }
    val infraComponents = environment.components.filter { it.type != ComponentTypeEnum.PROJECT }

    fun saveEnvironment(updatedEnv: EnvironmentDto) {
        scope.launch {
            saveError = null
            try {
                repository.environments.updateEnvironment(updatedEnv.id, updatedEnv)
                onUpdated()
            } catch (e: Exception) {
                saveError = "Chyba při ukládání: ${e.message}"
            }
        }
    }

    Column(modifier = modifier.padding(vertical = JervisSpacing.outerPadding)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = JervisSpacing.itemGap),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${environment.propertyMappings.size} mapování",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projectComponents.isNotEmpty() && infraComponents.isNotEmpty()) {
                    JSecondaryButton(onClick = { showAutoSuggest = !showAutoSuggest }) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-navrhnout")
                    }
                }
                JPrimaryButton(onClick = { showAddForm = !showAddForm }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Přidat")
                }
            }
        }

        // Auto-suggest panel
        AnimatedVisibility(
            visible = showAutoSuggest,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            AutoSuggestPanel(
                projectComponents = projectComponents,
                infraComponents = infraComponents,
                templates = templates,
                existingMappings = environment.propertyMappings,
                onApply = { newMappings ->
                    val updatedMappings = environment.propertyMappings + newMappings
                    saveEnvironment(environment.copy(propertyMappings = updatedMappings))
                    showAutoSuggest = false
                },
                onDismiss = { showAutoSuggest = false },
            )
        }

        // Manual add form
        AnimatedVisibility(
            visible = showAddForm,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            ManualMappingForm(
                projectComponents = projectComponents,
                infraComponents = infraComponents,
                onAdd = { mapping ->
                    val updatedMappings = environment.propertyMappings + mapping
                    saveEnvironment(environment.copy(propertyMappings = updatedMappings))
                    showAddForm = false
                },
                onCancel = { showAddForm = false },
            )
        }

        saveError?.let { errorMsg ->
            JErrorState(message = errorMsg)
            Spacer(Modifier.height(JervisSpacing.itemGap))
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        if (environment.propertyMappings.isEmpty()) {
            JEmptyState(
                message = if (projectComponents.isEmpty() || infraComponents.isEmpty()) {
                    "Mapování propojuje infra komponenty (DB, cache) s projekty přes ENV proměnné. " +
                        "Přidejte alespoň jeden PROJECT a jeden infrastrukturní komponent na záložce Komponenty."
                } else {
                    "Žádná mapování. Klikněte \"Auto-navrhnout\" pro automatické vygenerování " +
                        "na základě šablon, nebo \"Přidat\" pro ruční vytvoření."
                },
                icon = "\uD83D\uDD17",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                items(
                    environment.propertyMappings.indices.toList(),
                    key = { "${environment.propertyMappings[it].projectComponentId}_${environment.propertyMappings[it].propertyName}" },
                ) { index ->
                    val mapping = environment.propertyMappings[index]
                    val isExpanded = expandedMappingIndex == index

                    PropertyMappingCard(
                        mapping = mapping,
                        projectComponent = environment.components.find { it.id == mapping.projectComponentId },
                        targetComponent = environment.components.find { it.id == mapping.targetComponentId },
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedMappingIndex = if (isExpanded) null else index
                        },
                        onRemove = {
                            val updatedMappings = environment.propertyMappings.toMutableList()
                            updatedMappings.removeAt(index)
                            saveEnvironment(environment.copy(propertyMappings = updatedMappings))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Card showing a single property mapping: PROJECT comp → INFRA comp with ENV var.
 */
@Composable
private fun PropertyMappingCard(
    mapping: PropertyMappingDto,
    projectComponent: EnvironmentComponentDto?,
    targetComponent: EnvironmentComponentDto?,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRemove: () -> Unit,
) {
    JCard(onClick = onToggleExpand) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mapping.propertyName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${projectComponent?.name ?: "?"} \u2190 ${targetComponent?.name ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mapping.resolvedValue != null) {
                Text(
                    "resolved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            JRemoveIconButton(
                onConfirmed = onRemove,
                title = "Odebrat mapování?",
                message = "Mapování \"${mapping.propertyName}\" bude odebráno.",
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 28.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                JKeyValueRow("ENV proměnná", mapping.propertyName)
                JKeyValueRow("Šablona hodnoty", mapping.valueTemplate)
                mapping.resolvedValue?.let {
                    JKeyValueRow("Vyhodnocená hodnota", it)
                }
                JKeyValueRow(
                    "Projekt komponenta",
                    projectComponent?.let { "${it.name} (${componentTypeLabel(it.type)})" } ?: mapping.projectComponentId,
                )
                JKeyValueRow(
                    "Cílová komponenta",
                    targetComponent?.let { "${it.name} (${componentTypeLabel(it.type)})" } ?: mapping.targetComponentId,
                )
            }
        }
    }
}

/**
 * Auto-suggest panel: detects PROJECT→INFRA links and generates property mappings
 * from predefined templates.
 */
@Composable
private fun AutoSuggestPanel(
    projectComponents: List<EnvironmentComponentDto>,
    infraComponents: List<EnvironmentComponentDto>,
    templates: List<ComponentTemplateDto>,
    existingMappings: List<PropertyMappingDto>,
    onApply: (List<PropertyMappingDto>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Generate suggestions: for each project × infra pair, find matching templates
    val suggestions = remember(projectComponents, infraComponents, templates, existingMappings) {
        buildList {
            for (project in projectComponents) {
                for (infra in infraComponents) {
                    val template = templates.find { it.type == infra.type } ?: continue
                    for (mappingTemplate in template.propertyMappingTemplates) {
                        // Skip if already exists
                        val alreadyExists = existingMappings.any {
                            it.projectComponentId == project.id &&
                                it.targetComponentId == infra.id &&
                                it.propertyName == mappingTemplate.envVarName
                        }
                        if (!alreadyExists) {
                            add(
                                SuggestedMapping(
                                    projectComponent = project,
                                    infraComponent = infra,
                                    envVarName = mappingTemplate.envVarName,
                                    valueTemplate = mappingTemplate.valueTemplate,
                                    description = mappingTemplate.description,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    var selectedSuggestions by remember(suggestions) {
        mutableStateOf(suggestions.map { true }.toMutableList())
    }

    JSection(title = "Automatický návrh mapování") {
        if (suggestions.isEmpty()) {
            Text(
                "Všechna dostupná mapování jsou již vytvořena.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Nalezeno ${suggestions.size} mapování na základě šablon. Vyberte která přidat:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            suggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = selectedSuggestions.getOrElse(index) { true },
                        onCheckedChange = { checked ->
                            selectedSuggestions = selectedSuggestions.toMutableList().also { it[index] = checked }
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${suggestion.envVarName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${suggestion.projectComponent.name} \u2190 ${suggestion.infraComponent.name}: ${suggestion.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            suggestion.valueTemplate,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(JervisSpacing.fieldGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            JTextButton(onClick = onDismiss) { Text("Zrušit") }
            Spacer(Modifier.width(8.dp))
            JPrimaryButton(
                onClick = {
                    val toApply = suggestions.filterIndexed { index, _ ->
                        selectedSuggestions.getOrElse(index) { true }
                    }.map { suggestion ->
                        PropertyMappingDto(
                            projectComponentId = suggestion.projectComponent.id,
                            propertyName = suggestion.envVarName,
                            targetComponentId = suggestion.infraComponent.id,
                            valueTemplate = suggestion.valueTemplate,
                        )
                    }
                    onApply(toApply)
                },
                enabled = suggestions.isNotEmpty() && selectedSuggestions.any { it },
            ) { Text("Přidat vybrané") }
        }
    }
}

/**
 * Manual form for adding a single property mapping.
 */
@Composable
private fun ManualMappingForm(
    projectComponents: List<EnvironmentComponentDto>,
    infraComponents: List<EnvironmentComponentDto>,
    onAdd: (PropertyMappingDto) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedProject by remember { mutableStateOf(projectComponents.firstOrNull()) }
    var selectedInfra by remember { mutableStateOf(infraComponents.firstOrNull()) }
    var propertyName by remember { mutableStateOf("") }
    var valueTemplate by remember { mutableStateOf("") }

    JSection(title = "Nové mapování") {
        Text(
            "Mapování propojuje infrastrukturní komponentu (DB, Redis, ...) s projektovou " +
                "aplikací. Při provisionování se šablona vyhodnotí a výsledná hodnota se " +
                "nastaví jako ENV proměnná v kontejneru projektu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(JervisSpacing.fieldGap))

        // 1. Source: infra component (where data comes from)
        if (infraComponents.isNotEmpty()) {
            JDropdown(
                items = infraComponents,
                selectedItem = selectedInfra,
                onItemSelected = { selectedInfra = it },
                label = "Zdroj dat (infra komponenta)",
                itemLabel = { "${it.name} (${componentTypeLabel(it.type)})" },
            )
            Spacer(Modifier.height(JervisSpacing.fieldGap))
        }

        // 2. Target: project component (where ENV will be set)
        if (projectComponents.isNotEmpty()) {
            JDropdown(
                items = projectComponents,
                selectedItem = selectedProject,
                onItemSelected = { selectedProject = it },
                label = "Cíl (projektová aplikace)",
                itemLabel = { it.name },
            )
            Spacer(Modifier.height(JervisSpacing.fieldGap))
        }

        // 3. ENV variable name
        JTextField(
            value = propertyName,
            onValueChange = { propertyName = it },
            label = "Název ENV proměnné v cílovém kontejneru",
            placeholder = "např. SPRING_DATASOURCE_URL, REDIS_HOST",
            singleLine = true,
        )
        Spacer(Modifier.height(JervisSpacing.fieldGap))

        // 4. Value template
        JTextField(
            value = valueTemplate,
            onValueChange = { valueTemplate = it },
            label = "Šablona hodnoty (placeholdery se nahradí při provisionování)",
            placeholder = "např. jdbc:postgresql://{host}:{port}/db",
            singleLine = true,
        )
        Text(
            "Placeholdery: {host} = K8s service name infra komponenty, {port} = první port, " +
                "{name} = název komponenty, {env:VAR_NAME} = hodnota ENV z infra komponenty",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(JervisSpacing.fieldGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            JTextButton(onClick = onCancel) { Text("Zrušit") }
            Spacer(Modifier.width(8.dp))
            JPrimaryButton(
                onClick = {
                    val project = selectedProject ?: return@JPrimaryButton
                    val infra = selectedInfra ?: return@JPrimaryButton
                    onAdd(
                        PropertyMappingDto(
                            projectComponentId = project.id,
                            propertyName = propertyName.trim(),
                            targetComponentId = infra.id,
                            valueTemplate = valueTemplate.trim(),
                        ),
                    )
                },
                enabled = propertyName.isNotBlank() && valueTemplate.isNotBlank() &&
                    selectedProject != null && selectedInfra != null,
            ) { Text("Přidat") }
        }
    }
}

private data class SuggestedMapping(
    val projectComponent: EnvironmentComponentDto,
    val infraComponent: EnvironmentComponentDto,
    val envVarName: String,
    val valueTemplate: String,
    val description: String,
)
