package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.jervis.dto.openrouter.OpenRouterCatalogModelDto
import com.jervis.dto.openrouter.OpenRouterFallbackStrategy
import com.jervis.dto.openrouter.OpenRouterFiltersDto
import com.jervis.dto.openrouter.OpenRouterModelEntryDto
import com.jervis.dto.openrouter.OpenRouterModelUseCase
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.OpenRouterSettingsUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import kotlinx.coroutines.launch

@Composable
internal fun OpenRouterSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(OpenRouterSettingsDto()) }

    // Editable fields
    var apiKey by remember { mutableStateOf("") }
    var apiBaseUrl by remember { mutableStateOf("https://openrouter.ai/api/v1") }
    var enabled by remember { mutableStateOf(false) }
    var monthlyBudgetUsd by remember { mutableStateOf("0") }
    var fallbackStrategy by remember { mutableStateOf(OpenRouterFallbackStrategy.NEXT_IN_LIST) }

    // Filters
    var modelNameFilter by remember { mutableStateOf("") }
    var minContextLength by remember { mutableStateOf("0") }
    var maxInputPrice by remember { mutableStateOf("0") }
    var maxOutputPrice by remember { mutableStateOf("0") }
    var requireToolSupport by remember { mutableStateOf(false) }
    var requireStreaming by remember { mutableStateOf(true) }

    // Model list
    var models by remember { mutableStateOf<List<OpenRouterModelEntryDto>>(emptyList()) }

    // Catalog
    var catalogModels by remember { mutableStateOf<List<OpenRouterCatalogModelDto>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var showAddModelDialog by remember { mutableStateOf(false) }

    // Connection test
    var testingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<Boolean?>(null) }

    fun applySettings(dto: OpenRouterSettingsDto) {
        settings = dto
        apiKey = dto.apiKey
        apiBaseUrl = dto.apiBaseUrl
        enabled = dto.enabled
        monthlyBudgetUsd = if (dto.monthlyBudgetUsd > 0) dto.monthlyBudgetUsd.toString() else "0"
        fallbackStrategy = dto.fallbackStrategy
        modelNameFilter = dto.filters.modelNameFilter
        minContextLength = dto.filters.minContextLength.toString()
        maxInputPrice = if (dto.filters.maxInputPricePerMillion > 0) dto.filters.maxInputPricePerMillion.toString() else "0"
        maxOutputPrice = if (dto.filters.maxOutputPricePerMillion > 0) dto.filters.maxOutputPricePerMillion.toString() else "0"
        requireToolSupport = dto.filters.requireToolSupport
        requireStreaming = dto.filters.requireStreaming
        models = dto.models
    }

    fun loadSettings() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val dto = repository.openRouterSettings.getSettings()
                applySettings(dto)
            } catch (e: Exception) {
                error = "Chyba načítání: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadSettings() }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(
                message = error!!,
                onRetry = { loadSettings() },
            )
            else -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // --- API Connection ---
                    JSection(title = "Připojení k OpenRouter") {
                        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                            Text(
                                "Směrování LLM požadavků přes OpenRouter AI. Lokální P40 GPU se použije primárně, " +
                                    "OpenRouter převezme při plné frontě nebo překročení 48k tokenů kontextu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            JSwitch(
                                label = "Povolit OpenRouter",
                                description = "Globální přepínač – vypnutí deaktivuje veškeré směrování přes OpenRouter.",
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                            )

                            JTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    connectionTestResult = null
                                },
                                label = "API klíč",
                                placeholder = "sk-or-v1-...",
                            )

                            JTextField(
                                value = apiBaseUrl,
                                onValueChange = { apiBaseUrl = it },
                                label = "API Base URL",
                                placeholder = "https://openrouter.ai/api/v1",
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                JSecondaryButton(
                                    onClick = {
                                        scope.launch {
                                            testingConnection = true
                                            connectionTestResult = null
                                            try {
                                                // Save first so backend can use the key
                                                repository.openRouterSettings.updateSettings(
                                                    OpenRouterSettingsUpdateDto(apiKey = apiKey, apiBaseUrl = apiBaseUrl),
                                                )
                                                connectionTestResult = repository.openRouterSettings.testConnection()
                                            } catch (e: Exception) {
                                                connectionTestResult = false
                                            } finally {
                                                testingConnection = false
                                            }
                                        }
                                    },
                                    enabled = apiKey.isNotBlank() && !testingConnection,
                                ) {
                                    if (testingConnection) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (testingConnection) "Testuji..." else "Test připojení")
                                }

                                connectionTestResult?.let { success ->
                                    Icon(
                                        imageVector = if (success) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        tint = if (success) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        if (success) "Připojení OK" else "Připojení selhalo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (success) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }

                            JTextField(
                                value = monthlyBudgetUsd,
                                onValueChange = { monthlyBudgetUsd = it.filter { c -> c.isDigit() || c == '.' } },
                                label = "Měsíční rozpočet (USD, 0 = neomezeno)",
                                placeholder = "0",
                            )

                            JDropdown(
                                items = OpenRouterFallbackStrategy.entries,
                                selectedItem = fallbackStrategy,
                                onItemSelected = { fallbackStrategy = it },
                                label = "Strategie fallbacku",
                                itemLabel = { strategy ->
                                    when (strategy) {
                                        OpenRouterFallbackStrategy.NEXT_IN_LIST -> "Další model v pořadí"
                                        OpenRouterFallbackStrategy.OPENROUTER_AUTO -> "Automatický výběr OpenRouter"
                                        OpenRouterFallbackStrategy.FAIL -> "Selhání (návrat do lokální fronty)"
                                    }
                                },
                            )
                        }
                    }

                    // --- Filters ---
                    JSection(title = "Filtry modelů") {
                        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                            Text(
                                "Filtry omezují, které modely se zobrazí v katalogu a mohou být přidány do seznamu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            JTextField(
                                value = modelNameFilter,
                                onValueChange = { modelNameFilter = it },
                                label = "Filtr názvu modelu",
                                placeholder = "např. gpt-4, claude, llama",
                            )

                            JTextField(
                                value = minContextLength,
                                onValueChange = { minContextLength = it.filter { c -> c.isDigit() } },
                                label = "Minimální kontext (tokeny, 0 = bez omezení)",
                                placeholder = "0",
                            )

                            JTextField(
                                value = maxInputPrice,
                                onValueChange = { maxInputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                                label = "Max. cena vstup (USD/1M tokenů, 0 = bez omezení)",
                                placeholder = "0",
                            )

                            JTextField(
                                value = maxOutputPrice,
                                onValueChange = { maxOutputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                                label = "Max. cena výstup (USD/1M tokenů, 0 = bez omezení)",
                                placeholder = "0",
                            )

                            JCheckboxRow(
                                label = "Vyžadovat podporu tools/function calling",
                                checked = requireToolSupport,
                                onCheckedChange = { requireToolSupport = it },
                            )

                            JCheckboxRow(
                                label = "Vyžadovat podporu streamování",
                                checked = requireStreaming,
                                onCheckedChange = { requireStreaming = it },
                            )
                        }
                    }

                    // --- Model Priority List ---
                    JSection(title = "Prioritní seznam modelů") {
                        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                            Text(
                                "Seřazený seznam modelů dostupných pro routing. " +
                                    "Pořadí určuje prioritu – model na pozici 1 se použije jako první. " +
                                    "Orchestrátor, chat i KB vybírají model z tohoto seznamu podle typu úlohy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(4.dp))

                            if (models.isEmpty()) {
                                JEmptyState(
                                    message = "Žádné modely v seznamu. Přidejte modely z katalogu OpenRouter.",
                                    emojiIcon = "🤖",
                                )
                            } else {
                                models.forEachIndexed { index, model ->
                                    ModelEntryCard(
                                        model = model,
                                        index = index,
                                        isFirst = index == 0,
                                        isLast = index == models.lastIndex,
                                        onMoveUp = {
                                            if (index > 0) {
                                                val list = models.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index - 1, item)
                                                models = list
                                            }
                                        },
                                        onMoveDown = {
                                            if (index < models.lastIndex) {
                                                val list = models.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index + 1, item)
                                                models = list
                                            }
                                        },
                                        onToggle = {
                                            models = models.toMutableList().also { list ->
                                                list[index] = model.copy(enabled = !model.enabled)
                                            }
                                        },
                                        onRemove = {
                                            models = models.filterIndexed { i, _ -> i != index }
                                        },
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            JPrimaryButton(
                                onClick = {
                                    scope.launch {
                                        loadingCatalog = true
                                        try {
                                            // Save current filters before fetching
                                            repository.openRouterSettings.updateSettings(
                                                OpenRouterSettingsUpdateDto(
                                                    apiKey = apiKey,
                                                    apiBaseUrl = apiBaseUrl,
                                                    filters = OpenRouterFiltersDto(
                                                        modelNameFilter = modelNameFilter,
                                                        minContextLength = minContextLength.toIntOrNull() ?: 0,
                                                        maxInputPricePerMillion = maxInputPrice.toDoubleOrNull() ?: 0.0,
                                                        maxOutputPricePerMillion = maxOutputPrice.toDoubleOrNull() ?: 0.0,
                                                        requireToolSupport = requireToolSupport,
                                                        requireStreaming = requireStreaming,
                                                    ),
                                                ),
                                            )
                                            catalogModels = repository.openRouterSettings.fetchCatalogModels()
                                            showAddModelDialog = true
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Chyba načítání katalogu: ${e.message}")
                                        } finally {
                                            loadingCatalog = false
                                        }
                                    }
                                },
                                enabled = apiKey.isNotBlank() && !loadingCatalog,
                            ) {
                                if (loadingCatalog) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (loadingCatalog) "Načítám katalog..." else "Přidat model z katalogu")
                            }
                        }
                    }

                    // --- Save Button ---
                    JPrimaryButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val updated = repository.openRouterSettings.updateSettings(
                                        OpenRouterSettingsUpdateDto(
                                            apiKey = apiKey,
                                            apiBaseUrl = apiBaseUrl,
                                            enabled = enabled,
                                            filters = OpenRouterFiltersDto(
                                                modelNameFilter = modelNameFilter,
                                                minContextLength = minContextLength.toIntOrNull() ?: 0,
                                                maxInputPricePerMillion = maxInputPrice.toDoubleOrNull() ?: 0.0,
                                                maxOutputPricePerMillion = maxOutputPrice.toDoubleOrNull() ?: 0.0,
                                                requireToolSupport = requireToolSupport,
                                                requireStreaming = requireStreaming,
                                            ),
                                            models = models,
                                            monthlyBudgetUsd = monthlyBudgetUsd.toDoubleOrNull() ?: 0.0,
                                            fallbackStrategy = fallbackStrategy,
                                        ),
                                    )
                                    applySettings(updated)
                                    snackbarHostState.showSnackbar("Nastavení OpenRouter uloženo")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba ukládání: ${e.message}")
                                }
                            }
                        },
                    ) {
                        Text("Uložit nastavení")
                    }

                    // Bottom spacing
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        JSnackbarHost(snackbarHostState)
    }

    // Add Model from Catalog Dialog
    if (showAddModelDialog) {
        AddModelFromCatalogDialog(
            catalogModels = catalogModels,
            existingModelIds = models.map { it.modelId }.toSet(),
            onAdd = { catalogModel ->
                models = models + OpenRouterModelEntryDto(
                    modelId = catalogModel.id,
                    displayName = catalogModel.name,
                    enabled = true,
                    maxContextTokens = catalogModel.contextLength,
                    inputPricePerMillion = catalogModel.inputPricePerMillion,
                    outputPricePerMillion = catalogModel.outputPricePerMillion,
                )
            },
            onDismiss = { showAddModelDialog = false },
        )
    }
}

@Composable
private fun ModelEntryCard(
    model: OpenRouterModelEntryDto,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Priority number
            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.titleMedium,
                color = if (model.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.width(12.dp))

            // Model info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName.ifEmpty { model.modelId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    buildString {
                        append(model.modelId)
                        if (model.maxContextTokens > 0) {
                            append(" · ${model.maxContextTokens / 1000}k ctx")
                        }
                        if (model.inputPricePerMillion > 0) {
                            append(" · \$${formatPrice(model.inputPricePerMillion)}/\$${formatPrice(model.outputPricePerMillion)} /1M")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.preferredFor.isNotEmpty()) {
                    Text(
                        model.preferredFor.joinToString(", ") { useCase ->
                            when (useCase) {
                                OpenRouterModelUseCase.CHAT -> "Chat"
                                OpenRouterModelUseCase.CODING -> "Kódování"
                                OpenRouterModelUseCase.REASONING -> "Reasoning"
                                OpenRouterModelUseCase.LARGE_CONTEXT -> "Velký kontext"
                                OpenRouterModelUseCase.KNOWLEDGE_BASE -> "KB"
                                OpenRouterModelUseCase.ORCHESTRATION -> "Orchestrace"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            // Actions
            JIconButton(
                onClick = onMoveUp,
                icon = Icons.Default.ArrowUpward,
                contentDescription = "Posunout nahoru",
                enabled = !isFirst,
            )
            JIconButton(
                onClick = onMoveDown,
                icon = Icons.Default.ArrowDownward,
                contentDescription = "Posunout dolů",
                enabled = !isLast,
            )
            JIconButton(
                onClick = onToggle,
                icon = if (model.enabled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (model.enabled) "Deaktivovat" else "Aktivovat",
            )
            JRemoveIconButton(
                onConfirmed = onRemove,
                title = "Odebrat model?",
                message = "Model \"${model.displayName.ifEmpty { model.modelId }}\" bude odebrán ze seznamu.",
            )
        }
    }
}

@Composable
private fun AddModelFromCatalogDialog(
    catalogModels: List<OpenRouterCatalogModelDto>,
    existingModelIds: Set<String>,
    onAdd: (OpenRouterCatalogModelDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchFilter by remember { mutableStateOf("") }

    val filtered = remember(catalogModels, searchFilter, existingModelIds) {
        catalogModels
            .filter { it.id !in existingModelIds }
            .filter {
                if (searchFilter.isBlank()) true
                else searchFilter.lowercase().let { q ->
                    q in it.id.lowercase() || q in it.name.lowercase()
                }
            }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Přidat model z katalogu",
                    style = MaterialTheme.typography.headlineSmall,
                )

                Text(
                    "${catalogModels.size} modelů k dispozici, ${existingModelIds.size} již přidáno",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                JTextField(
                    value = searchFilter,
                    onValueChange = { searchFilter = it },
                    label = "Hledat model",
                    placeholder = "gpt-4, claude, llama...",
                )

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            "Žádné modely odpovídající filtru.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        filtered.take(50).forEach { model ->
                            JCard(
                                onClick = {
                                    onAdd(model)
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = JervisSpacing.touchTarget),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            model.name.ifEmpty { model.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            buildString {
                                                append(model.id)
                                                if (model.contextLength > 0) append(" · ${model.contextLength / 1000}k ctx")
                                                if (model.inputPricePerMillion > 0) {
                                                    append(" · \$${formatPrice(model.inputPricePerMillion)}/\$${formatPrice(model.outputPricePerMillion)} /1M")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Přidat",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (filtered.size > 50) {
                            Text(
                                "... a dalších ${filtered.size - 50} modelů (upřesněte filtr)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    JSecondaryButton(onClick = onDismiss) {
                        Text("Zavřít")
                    }
                }
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    return if (price < 0.01) {
        "%.4f".format(price)
    } else if (price < 1.0) {
        "%.2f".format(price)
    } else {
        "%.1f".format(price)
    }
}
