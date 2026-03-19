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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.jervis.dto.openrouter.ModelErrorDto
import com.jervis.dto.openrouter.ModelQueueDto
import com.jervis.dto.openrouter.OpenRouterCatalogModelDto
import com.jervis.dto.openrouter.OpenRouterFallbackStrategy
import com.jervis.dto.openrouter.OpenRouterFiltersDto
import com.jervis.dto.openrouter.OpenRouterModelEntryDto
import com.jervis.dto.openrouter.OpenRouterModelUseCase
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.OpenRouterSettingsUpdateDto
import com.jervis.dto.openrouter.QueueModelEntryDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

    // Model list (legacy flat list)
    var models by remember { mutableStateOf<List<OpenRouterModelEntryDto>>(emptyList()) }

    // Model queues (FREE, PAID, PREMIUM)
    val queueNames = listOf("FREE", "PAID", "PREMIUM")
    var modelQueues by remember { mutableStateOf<Map<String, List<QueueModelEntryDto>>>(emptyMap()) }
    var selectedQueueTab by remember { mutableStateOf(0) }

    // Catalog
    var catalogModels by remember { mutableStateOf<List<OpenRouterCatalogModelDto>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var addModelTargetQueue by remember { mutableStateOf("FREE") }

    // Connection test
    var testingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<Boolean?>(null) }

    // Model errors
    var modelErrors by remember { mutableStateOf<List<ModelErrorDto>>(emptyList()) }
    var loadingErrors by remember { mutableStateOf(false) }

    fun applySettings(dto: OpenRouterSettingsDto) {
        settings = dto
        apiKey = dto.apiKey
        apiBaseUrl = dto.apiBaseUrl
        enabled = dto.enabled
        monthlyBudgetUsd = if (dto.monthlyBudgetUsd > 0) dto.monthlyBudgetUsd.toString() else "0"
        fallbackStrategy = dto.fallbackStrategy
        models = dto.models
        modelQueues = dto.modelQueues.associate { it.name to it.models }
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

    // Auto-refresh model errors every 30s
    LaunchedEffect(Unit) {
        while (true) {
            try {
                modelErrors = repository.openRouterSettings.getModelErrors()
            } catch (_: Exception) {
                // Ignore — router may be unavailable
            }
            delay(30_000)
        }
    }

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

                    // --- Model Queues (4-tier routing) ---
                    JSection(title = "Fronty modelů") {
                        Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                            Text(
                                "4 fronty pro tiered routing. Při busy GPU se vybírá fronta dle nastavení projektu " +
                                    "(maxOpenRouterTier). Pořadí modelů v každé frontě určuje prioritu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            val queueLabels = listOf("Free", "Paid", "Premium")

                            TabRow(
                                selectedTabIndex = selectedQueueTab,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                queueLabels.forEachIndexed { index, label ->
                                    Tab(
                                        selected = selectedQueueTab == index,
                                        onClick = { selectedQueueTab = index },
                                        text = { Text(label) },
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            val currentQueueName = queueNames[selectedQueueTab]
                            val currentModels = modelQueues[currentQueueName] ?: emptyList()

                            Text(
                                when (currentQueueName) {
                                    "FREE" -> "Automatický fallback při busy GPU. Pouze free modely z OpenRouteru."
                                    "PAID" -> "Placené modely (Haiku, GPT-4o-mini). Aktivní pokud projekt povoluje tier PAID nebo vyšší."
                                    "PREMIUM" -> "Premium reasoning modely (Sonnet, o3-mini). Aktivní pokud projekt povoluje tier PREMIUM."
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(4.dp))

                            if (currentModels.isEmpty()) {
                                JEmptyState(
                                    message = "Žádné modely v této frontě.",
                                    icon = "🤖",
                                )
                            } else {
                                currentModels.forEachIndexed { index, entry ->
                                    val entryError = modelErrors.find { it.modelId == entry.modelId }
                                    QueueModelCard(
                                        entry = entry,
                                        index = index,
                                        isFirst = index == 0,
                                        isLast = index == currentModels.lastIndex,
                                        errorInfo = entryError,
                                        onMoveUp = {
                                            if (index > 0) {
                                                val list = currentModels.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index - 1, item)
                                                modelQueues = modelQueues + (currentQueueName to list)
                                            }
                                        },
                                        onMoveDown = {
                                            if (index < currentModels.lastIndex) {
                                                val list = currentModels.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index + 1, item)
                                                modelQueues = modelQueues + (currentQueueName to list)
                                            }
                                        },
                                        onToggle = {
                                            val list = currentModels.toMutableList()
                                            list[index] = entry.copy(enabled = !entry.enabled)
                                            modelQueues = modelQueues + (currentQueueName to list)
                                        },
                                        onRemove = {
                                            modelQueues = modelQueues + (currentQueueName to currentModels.filterIndexed { i, _ -> i != index })
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
                                            // Fetch ALL models — filtering is done client-side in the dialog
                                            catalogModels = repository.openRouterSettings.fetchCatalogModels(OpenRouterFiltersDto())
                                            addModelTargetQueue = currentQueueName
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
                                Text(if (loadingCatalog) "Načítám katalog..." else "Přidat model do fronty")
                            }
                        }
                    }

                    // --- Model Errors ---
                    if (modelErrors.isNotEmpty()) {
                        JSection(title = "Chyby modelů") {
                            Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                                Text(
                                    "Modely s opakovanými chybami jsou automaticky deaktivovány (po 3 chybách za sebou).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                modelErrors.forEach { errorModel ->
                                    ModelErrorCard(
                                        error = errorModel,
                                        onReset = {
                                            scope.launch {
                                                try {
                                                    repository.openRouterSettings.resetModelError(errorModel.modelId)
                                                    // Refresh errors
                                                    modelErrors = repository.openRouterSettings.getModelErrors()
                                                    snackbarHostState.showSnackbar(
                                                        "Model ${errorModel.modelId} resetován",
                                                    )
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar(
                                                        "Chyba resetu: ${e.message}",
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    JSecondaryButton(
                                        onClick = {
                                            scope.launch {
                                                loadingErrors = true
                                                try {
                                                    modelErrors = repository.openRouterSettings.getModelErrors()
                                                } catch (_: Exception) {}
                                                loadingErrors = false
                                            }
                                        },
                                        enabled = !loadingErrors,
                                    ) {
                                        if (loadingErrors) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Obnovit")
                                    }
                                }
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
                                            models = models,
                                            monthlyBudgetUsd = monthlyBudgetUsd.toDoubleOrNull() ?: 0.0,
                                            fallbackStrategy = fallbackStrategy,
                                            modelQueues = queueNames.map { name ->
                                                ModelQueueDto(
                                                    name = name,
                                                    models = modelQueues[name] ?: emptyList(),
                                                )
                                            },
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
        val targetQueue = addModelTargetQueue
        val existingInQueue = (modelQueues[targetQueue] ?: emptyList()).map { it.modelId }.toSet()
        AddModelFromCatalogDialog(
            catalogModels = catalogModels,
            existingModelIds = existingInQueue,
            onAdd = { catalogModel ->
                val currentList = modelQueues[targetQueue] ?: emptyList()
                modelQueues = modelQueues + (targetQueue to currentList + QueueModelEntryDto(
                    modelId = catalogModel.id,
                    isLocal = false,
                    maxContextTokens = catalogModel.contextLength,
                    enabled = true,
                    label = catalogModel.name,
                ))
            },
            onDismiss = { showAddModelDialog = false },
        )
    }
}

@Composable
private fun QueueModelCard(
    entry: QueueModelEntryDto,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    errorInfo: ModelErrorDto? = null,
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
            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.width(12.dp))

            if (errorInfo != null) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Model má chyby",
                    tint = if (errorInfo.disabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.label.ifEmpty { entry.modelId },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    buildString {
                        append(entry.modelId)
                        if (entry.isLocal) append(" (lokální GPU)")
                        if (entry.maxContextTokens > 0) {
                            append(" · ${entry.maxContextTokens / 1000}k ctx")
                        }
                        if (errorInfo != null) {
                            if (errorInfo.disabled) {
                                append(" · DISABLED")
                            } else {
                                append(" · ${errorInfo.errorCount}/3 chyb")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorInfo?.disabled == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

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
                icon = if (entry.enabled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (entry.enabled) "Deaktivovat" else "Aktivovat",
            )
            JRemoveIconButton(
                onConfirmed = onRemove,
                title = "Odebrat model?",
                message = "Model \"${entry.label.ifEmpty { entry.modelId }}\" bude odebrán z fronty.",
            )
        }
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
    var minContextLength by remember { mutableStateOf("") }
    var maxInputPrice by remember { mutableStateOf("") }
    var maxOutputPrice by remember { mutableStateOf("") }
    var requireToolSupport by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    val filtered = remember(
        catalogModels, searchFilter, existingModelIds,
        minContextLength, maxInputPrice, maxOutputPrice, requireToolSupport,
    ) {
        val minCtx = minContextLength.toIntOrNull() ?: 0
        val maxIn = maxInputPrice.toDoubleOrNull() ?: 0.0
        val maxOut = maxOutputPrice.toDoubleOrNull() ?: 0.0
        catalogModels
            .filter { it.id !in existingModelIds }
            .filter {
                if (searchFilter.isBlank()) true
                else searchFilter.lowercase().let { q ->
                    q in it.id.lowercase() || q in it.name.lowercase()
                }
            }
            .filter { if (minCtx > 0) it.contextLength >= minCtx else true }
            .filter { if (maxIn > 0) it.inputPricePerMillion <= maxIn else true }
            .filter { if (maxOut > 0) it.outputPricePerMillion <= maxOut else true }
            .filter { if (requireToolSupport) it.supportsTools else true }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
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
                    "${catalogModels.size} modelů celkem, ${filtered.size} po filtraci, ${existingModelIds.size} již přidáno",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                JTextField(
                    value = searchFilter,
                    onValueChange = { searchFilter = it },
                    label = "Hledat model",
                    placeholder = "gpt-4, claude, llama...",
                )

                // Expandable filters section
                JSecondaryButton(
                    onClick = { showFilters = !showFilters },
                ) {
                    Text(if (showFilters) "Skrýt filtry" else "Zobrazit filtry")
                }

                if (showFilters) {
                    Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
                        JTextField(
                            value = minContextLength,
                            onValueChange = { minContextLength = it.filter { c -> c.isDigit() } },
                            label = "Minimální kontext (tokeny)",
                            placeholder = "0 = bez omezení",
                        )

                        JTextField(
                            value = maxInputPrice,
                            onValueChange = { maxInputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                            label = "Max. cena vstup (USD/1M tokenů)",
                            placeholder = "0 = bez omezení",
                        )

                        JTextField(
                            value = maxOutputPrice,
                            onValueChange = { maxOutputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                            label = "Max. cena výstup (USD/1M tokenů)",
                            placeholder = "0 = bez omezení",
                        )

                        JCheckboxRow(
                            label = "Vyžadovat podporu tools/function calling",
                            checked = requireToolSupport,
                            onCheckedChange = { requireToolSupport = it },
                        )
                    }
                }

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
                        filtered.take(100).forEach { model ->
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
                                                if (model.supportsTools) append(" · tools")
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
                        if (filtered.size > 100) {
                            Text(
                                "... a dalších ${filtered.size - 100} modelů (upřesněte filtr)",
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

@Composable
private fun ModelErrorCard(
    error: ModelErrorDto,
    onReset: () -> Unit,
) {
    JCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header: icon + model name + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )

                Text(
                    error.modelId,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )

                JStatusBadge(
                    status = if (error.disabled) "DISABLED" else "${error.errorCount}/3",
                )
            }

            // Error count summary
            Text(
                "${error.errorCount}× chyba${if (error.errorCount != 1) "" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Error messages
            if (error.errors.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    error.errors.sortedByDescending { it.timestamp }.forEach { entry ->
                        val timeStr = formatErrorTimestamp(entry.timestamp)
                        Text(
                            "$timeStr  ${entry.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }

            // Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                JSecondaryButton(onClick = onReset) {
                    Text("Resetovat")
                }
            }
        }
    }
}

private fun formatErrorTimestamp(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    return try {
        val dt = Instant.fromEpochSeconds(epochSeconds.toLong())
            .toLocalDateTime(TimeZone.currentSystemDefault())
        "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        ""
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price < 0.01 -> {
            val scaled = (price * 10000).toLong()
            "${scaled / 10000}.${(scaled % 10000).toString().padStart(4, '0')}"
        }
        price < 1.0 -> {
            val scaled = (price * 100).toLong()
            "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
        }
        else -> {
            val scaled = (price * 10).toLong()
            "${scaled / 10}.${scaled % 10}"
        }
    }
}
