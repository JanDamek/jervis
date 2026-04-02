package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.dto.openrouter.ModelErrorDto
import com.jervis.dto.openrouter.ModelTestResultDto
import com.jervis.dto.openrouter.ModelQueueDto
import com.jervis.dto.openrouter.OpenRouterCatalogModelDto
import com.jervis.dto.openrouter.OpenRouterFiltersDto
import com.jervis.dto.openrouter.OpenRouterSettingsDto
import com.jervis.dto.openrouter.OpenRouterSettingsUpdateDto
import com.jervis.dto.openrouter.QueueModelEntryDto
import com.jervis.di.JervisRepository
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun OpenRouterSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(OpenRouterSettingsDto()) }

    var enabled by remember { mutableStateOf(false) }

    val queueNames = listOf("FREE", "PAID", "PREMIUM")
    var modelQueues by remember { mutableStateOf<Map<String, List<QueueModelEntryDto>>>(emptyMap()) }
    var selectedQueueTab by remember { mutableStateOf(0) }

    // Saved state for unsaved changes detection
    var savedEnabled by remember { mutableStateOf(false) }
    var savedModelQueues by remember { mutableStateOf<Map<String, List<QueueModelEntryDto>>>(emptyMap()) }

    val hasUnsavedChanges = enabled != savedEnabled ||
        queueNames.any { name ->
            val current = modelQueues[name] ?: emptyList()
            val saved = savedModelQueues[name] ?: emptyList()
            current.map { it.modelId to it.enabled } != saved.map { it.modelId to it.enabled }
        }

    var catalogModels by remember { mutableStateOf<List<OpenRouterCatalogModelDto>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var addModelTargetQueue by remember { mutableStateOf("FREE") }

    var modelErrors by remember { mutableStateOf<List<ModelErrorDto>>(emptyList()) }

    fun applySettings(dto: OpenRouterSettingsDto) {
        settings = dto
        enabled = dto.enabled
        modelQueues = dto.modelQueues.associate { it.name to it.models }
        savedEnabled = dto.enabled
        savedModelQueues = dto.modelQueues.associate { it.name to it.models }
    }

    fun loadSettings() {
        scope.launch {
            isLoading = true
            error = null
            try {
                applySettings(repository.openRouterSettings.getSettings())
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) { loadSettings() }

    // Auto-refresh errors every 30s
    LaunchedEffect(Unit) {
        while (true) {
            try { modelErrors = repository.openRouterSettings.getModelErrors() } catch (_: Exception) {}
            delay(30_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(message = error!!, onRetry = { loadSettings() })
            else -> Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // --- Global toggle ---
                JSection(title = "OpenRouter") {
                    JSwitch(
                        label = "Povolit OpenRouter",
                        description = "Směrování přes OpenRouter při busy GPU nebo překročení 48k ctx.",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }

                // --- Model Queues ---
                JSection(title = "Fronty modelů") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val queueLabels = listOf("Free", "Paid", "Premium")
                        TabRow(
                            selectedTabIndex = selectedQueueTab,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            queueLabels.forEachIndexed { index, label ->
                                val queueModels = modelQueues[queueNames[index]] ?: emptyList()
                                val modelCount = queueModels.size
                                Tab(
                                    selected = selectedQueueTab == index,
                                    onClick = { selectedQueueTab = index },
                                    text = { Text("$label ($modelCount)") },
                                )
                            }
                        }

                        val currentQueueName = queueNames[selectedQueueTab]
                        val currentModels = modelQueues[currentQueueName] ?: emptyList()

                        if (currentModels.isEmpty()) {
                            JEmptyState(message = "Fronta je prázdná")
                        } else {
                            currentModels.forEachIndexed { index, entry ->
                                key(entry.modelId) {
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
                                    onTest = {
                                        repository.openRouterSettings.testModel(entry.modelId)
                                    },
                                    onReset = if (entryError?.disabled == true) {
                                        {
                                            scope.launch {
                                                try {
                                                    repository.openRouterSettings.resetModelError(entry.modelId)
                                                    modelErrors = repository.openRouterSettings.getModelErrors()
                                                    snackbarHostState.showSnackbar("Model ${entry.modelId} resetován")
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                                }
                                            }
                                        }
                                    } else null,
                                )
                                } // key
                            }
                        }

                        // Add model button
                        JSecondaryButton(
                            onClick = {
                                scope.launch {
                                    loadingCatalog = true
                                    try {
                                        catalogModels = repository.openRouterSettings.fetchCatalogModels(OpenRouterFiltersDto())
                                        addModelTargetQueue = currentQueueName
                                        showAddModelDialog = true
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    } finally {
                                        loadingCatalog = false
                                    }
                                }
                            },
                            enabled = !loadingCatalog,
                        ) {
                            if (loadingCatalog) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Přidat model")
                        }
                    }
                }

                // --- Save ---
                if (hasUnsavedChanges) {
                    Text(
                        "Neuložené změny",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                JPrimaryButton(
                    onClick = {
                        scope.launch {
                            try {
                                val updated = repository.openRouterSettings.updateSettings(
                                    OpenRouterSettingsUpdateDto(
                                        enabled = enabled,
                                        modelQueues = queueNames.map { name ->
                                            ModelQueueDto(name = name, models = modelQueues[name] ?: emptyList())
                                        },
                                    ),
                                )
                                applySettings(updated)
                                snackbarHostState.showSnackbar("Uloženo")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                            }
                        }
                    },
                    enabled = hasUnsavedChanges,
                ) { Text(if (hasUnsavedChanges) "Uložit změny" else "Uloženo") }

                Spacer(Modifier.height(16.dp))
            }
        }

        JSnackbarHost(snackbarHostState)
    }

    // Add Model Dialog
    if (showAddModelDialog) {
        val targetQueue = addModelTargetQueue
        val existingInQueue = (modelQueues[targetQueue] ?: emptyList()).map { it.modelId }.toSet()
        AddModelFromCatalogDialog(
            catalogModels = catalogModels,
            existingModelIds = existingInQueue,
            targetQueueName = targetQueue,
            onAdd = { catalogModel ->
                val currentList = modelQueues[targetQueue] ?: emptyList()
                modelQueues = modelQueues + (targetQueue to currentList + QueueModelEntryDto(
                    modelId = catalogModel.id,
                    isLocal = false,
                    maxContextTokens = catalogModel.contextLength,
                    enabled = true,
                    label = catalogModel.name,
                    capabilities = catalogModel.capabilities,
                    inputPricePerMillion = catalogModel.inputPricePerMillion,
                    outputPricePerMillion = catalogModel.outputPricePerMillion,
                    supportsTools = catalogModel.supportsTools,
                    provider = catalogModel.provider,
                ))
            },
            onDismiss = { showAddModelDialog = false },
        )
    }
}

// ── Queue Model Card ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
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
    onTest: suspend () -> ModelTestResultDto? = { null },
    onReset: (() -> Unit)? = null,
) {
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ModelTestResultDto?>(null) }
    val scope = rememberCoroutineScope()

    val isDisabled = errorInfo?.disabled == true

    JCard {
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (!entry.enabled) Modifier.alpha(0.5f) else Modifier),
        ) {
            // Main row
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = JervisSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Priority number
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isDisabled -> MaterialTheme.colorScheme.error
                        !entry.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(12.dp))

                if (isDisabled) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }

                // Model info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.label.ifEmpty { entry.modelId },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.enabled && !isDisabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    // Detail line: id · context · price · stats
                    Text(
                        buildString {
                            append(entry.modelId)
                            // Context
                            if (entry.maxContextTokens > 0) {
                                val ctxK = entry.maxContextTokens / 1000
                                append(if (ctxK >= 1000) " · ${ctxK / 1000}M ctx" else " · ${ctxK}k ctx")
                            }
                            // Price
                            if (entry.inputPricePerMillion > 0 || entry.outputPricePerMillion > 0) {
                                append(" · \$${formatPrice(entry.inputPricePerMillion)}/\$${formatPrice(entry.outputPricePerMillion)}")
                            } else if (!entry.isLocal) {
                                append(" · FREE")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDisabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Stats line (if any calls recorded)
                    if (entry.stats.callCount > 0) {
                        val avgS = if (entry.stats.callCount > 0) {
                            "%.1f".format(entry.stats.totalTimeS / entry.stats.callCount)
                        } else "?"
                        Text(
                            buildString {
                                append("${entry.stats.callCount} volání · avg ${avgS}s")
                                if (entry.stats.tokensPerS > 0) {
                                    append(" · ${"%.0f".format(entry.stats.tokensPerS)} tok/s")
                                }
                                if (entry.stats.totalOutputTokens > 0) {
                                    val totalK = entry.stats.totalOutputTokens / 1000.0
                                    append(" · ${"%.1f".format(totalK)}k out tok")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        )
                    }

                    // Error info
                    if (isDisabled) {
                        Text(
                            "DISABLED — ${errorInfo?.errorCount ?: 0} chyb",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (errorInfo != null && errorInfo.errorCount > 0) {
                        Text(
                            "${errorInfo.errorCount}/3 chyb",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }

                    // Capability badges
                    if (entry.capabilities.isNotEmpty() || !entry.supportsTools) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            if (entry.supportsTools) {
                                CapabilityBadge("Tools", MaterialTheme.colorScheme.primary)
                            } else if (!entry.isLocal) {
                                CapabilityBadge("No Tools", MaterialTheme.colorScheme.error)
                            }
                            entry.capabilities.forEach { cap ->
                                val pair: Pair<String, androidx.compose.ui.graphics.Color>? = when (cap) {
                                    "visual" -> "VL" to MaterialTheme.colorScheme.tertiary
                                    "thinking" -> "Think" to MaterialTheme.colorScheme.secondary
                                    "coding" -> "Code" to MaterialTheme.colorScheme.secondary
                                    "chat" -> null
                                    "extraction" -> null
                                    else -> cap to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                if (pair != null) {
                                    CapabilityBadge(pair.first, pair.second)
                                }
                            }
                        }
                    }
                }

                // Actions
                if (!entry.isLocal) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        JIconButton(
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = null
                                    try {
                                        testResult = onTest()
                                    } catch (_: Exception) {
                                        testResult = ModelTestResultDto(ok = false, modelId = entry.modelId, error = "Selhalo")
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            icon = Icons.Default.Speed,
                            contentDescription = "Test",
                        )
                    }
                }

                if (isDisabled && onReset != null) {
                    JIconButton(
                        onClick = onReset,
                        icon = Icons.Default.Refresh,
                        contentDescription = "Resetovat",
                    )
                }

                JIconButton(onClick = onMoveUp, icon = Icons.Default.ArrowUpward, contentDescription = "Nahoru", enabled = !isFirst)
                JIconButton(onClick = onMoveDown, icon = Icons.Default.ArrowDownward, contentDescription = "Dolů", enabled = !isLast)

                // Enable/Disable toggle — visually distinct
                androidx.compose.material3.Switch(
                    checked = entry.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.height(32.dp),
                )

                JRemoveIconButton(
                    onConfirmed = onRemove,
                    title = "Odebrat model?",
                    message = "Model \"${entry.label.ifEmpty { entry.modelId }}\" bude odebrán z fronty.",
                )
            }

            // Test result
            testResult?.let { result ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (result.ok) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (result.ok) "OK (${result.responseMs}ms): ${result.responsePreview}" else "CHYBA: ${result.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

// ── Add Model Dialog ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddModelFromCatalogDialog(
    catalogModels: List<OpenRouterCatalogModelDto>,
    existingModelIds: Set<String>,
    targetQueueName: String,
    onAdd: (OpenRouterCatalogModelDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchFilter by remember { mutableStateOf("") }
    var filterFree by remember { mutableStateOf(targetQueueName == "FREE") }
    var filterTools by remember { mutableStateOf(false) }
    var filterVision by remember { mutableStateOf(false) }
    var filterMinCtx by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("price") } // price, context, name

    val filtered = remember(
        catalogModels, searchFilter, existingModelIds,
        filterFree, filterTools, filterVision, filterMinCtx, sortBy,
    ) {
        val minCtx = filterMinCtx.toIntOrNull() ?: 0
        catalogModels
            .filter { it.id !in existingModelIds }
            .filter {
                if (searchFilter.isBlank()) true
                else searchFilter.lowercase().let { q -> q in it.id.lowercase() || q in it.name.lowercase() }
            }
            .filter { if (filterFree) it.inputPricePerMillion == 0.0 && it.outputPricePerMillion == 0.0 else true }
            .filter { if (filterTools) it.supportsTools else true }
            .filter { if (filterVision) "visual" in it.capabilities else true }
            .filter { if (minCtx > 0) it.contextLength >= minCtx else true }
            .let { list ->
                when (sortBy) {
                    "price" -> list.sortedBy { it.inputPricePerMillion }
                    "context" -> list.sortedByDescending { it.contextLength }
                    "name" -> list.sortedBy { it.name.lowercase() }
                    else -> list
                }
            }
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
                Text("Přidat model do $targetQueueName", style = MaterialTheme.typography.headlineSmall)

                // Search
                JTextField(
                    value = searchFilter,
                    onValueChange = { searchFilter = it },
                    label = "Hledat",
                    placeholder = "gpt, claude, llama, gemini...",
                )

                // Filter chips
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filterFree,
                        onClick = { filterFree = !filterFree },
                        label = { Text("Free") },
                    )
                    FilterChip(
                        selected = filterTools,
                        onClick = { filterTools = !filterTools },
                        label = { Text("Tools") },
                    )
                    FilterChip(
                        selected = filterVision,
                        onClick = { filterVision = !filterVision },
                        label = { Text("Vision") },
                    )
                    FilterChip(
                        selected = sortBy == "price",
                        onClick = { sortBy = if (sortBy == "price") "name" else "price" },
                        label = { Text(if (sortBy == "price") "Cena ↑" else if (sortBy == "context") "Ctx ↓" else "A-Z") },
                    )
                    FilterChip(
                        selected = sortBy == "context",
                        onClick = { sortBy = if (sortBy == "context") "price" else "context" },
                        label = { Text("Kontext ↓") },
                    )
                }

                // Count
                Text(
                    "${filtered.size} modelů",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // Model list
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        JEmptyState(message = "Žádné modely")
                    } else {
                        filtered.take(80).forEach { model ->
                            JCard(onClick = { onAdd(model) }) {
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
                                                if (model.contextLength > 0) {
                                                    val ctxK = model.contextLength / 1000
                                                    append(if (ctxK >= 1000) " · ${ctxK / 1000}M ctx" else " · ${ctxK}k ctx")
                                                }
                                                if (model.inputPricePerMillion > 0) {
                                                    append(" · \$${formatPrice(model.inputPricePerMillion)}/\$${formatPrice(model.outputPricePerMillion)}")
                                                } else {
                                                    append(" · FREE")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        // Badges
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (model.supportsTools) CapabilityBadge("Tools", MaterialTheme.colorScheme.primary)
                                            if ("visual" in model.capabilities) CapabilityBadge("VL", MaterialTheme.colorScheme.tertiary)
                                        }
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
                        if (filtered.size > 80) {
                            Text(
                                "... a dalších ${filtered.size - 80} (upřesněte filtr)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    JSecondaryButton(onClick = onDismiss) { Text("Zavřít") }
                }
            }
        }
    }
}

private fun formatPrice(pricePerMillion: Double): String {
    return if (pricePerMillion >= 1.0) {
        "%.2f".format(pricePerMillion)
    } else if (pricePerMillion >= 0.01) {
        "%.3f".format(pricePerMillion)
    } else if (pricePerMillion > 0) {
        "%.4f".format(pricePerMillion)
    } else {
        "0"
    }
}
