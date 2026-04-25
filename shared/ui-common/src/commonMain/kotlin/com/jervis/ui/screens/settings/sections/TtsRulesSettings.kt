package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.di.JervisRepository
import com.jervis.dto.tts.TtsRuleDto
import com.jervis.dto.tts.TtsRulePreviewRequestDto
import com.jervis.dto.tts.TtsRuleScopeDto
import com.jervis.dto.tts.TtsRuleScopeTypeDto
import com.jervis.dto.tts.TtsRuleTypeDto
import com.jervis.dto.tts.TtsRulesSnapshotDto
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JConfirmDialog
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * TTS normalization dictionary — three rule types in one view
 * (acronym / strip / replace), grouped by [TtsRuleTypeDto].
 * Subscribes to `ttsRules.subscribeAll()` (push-only per guideline #9).
 */
@Composable
internal fun TtsRulesSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val rpcGeneration = LocalRpcGeneration.current

    val snapshotFlow = remember { MutableStateFlow<TtsRulesSnapshotDto?>(null) }
    val snapshot by snapshotFlow.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TtsRuleDto?>(null) }
    var toDelete by remember { mutableStateOf<TtsRuleDto?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(rpcGeneration) {
        repository.ttsRules.subscribeAll()
            .catch { e ->
                snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
            }
            .collect { snap -> snapshotFlow.value = snap }
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            JActionBar {
                TextButton(onClick = { showPreview = true }) {
                    Icon(Icons.Default.Preview, contentDescription = null)
                    Spacer(Modifier.width(JervisSpacing.itemGap))
                    Text("Náhled")
                }
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(JervisSpacing.itemGap))
                    Text("Přidat pravidlo")
                }
            }

            val current = snapshot
            when {
                current == null -> JCenteredLoading()
                current.rules.isEmpty() -> JEmptyState(
                    message = "Zatím žádná pravidla. Přidej tlačítkem výše nebo v chatu: „zapiš, že BMS se čte bé-em-es“.",
                )
                else -> TtsRuleTypeDto.entries.forEach { type ->
                    val group = current.rules.filter { it.type == type }
                    if (group.isEmpty()) return@forEach
                    JSection(title = ttsRuleTypeLabel(type) + " (${group.size})") {
                        group.forEach { rule ->
                            TtsRuleRow(
                                rule = rule,
                                onEdit = { editing = rule },
                                onDelete = { toDelete = rule },
                            )
                        }
                    }
                }
            }
        }

        JSnackbarHost(snackbarHostState)
    }

    if (showAddDialog) {
        TtsRuleEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { draft ->
                scope.launch {
                    try {
                        repository.ttsRules.add(draft)
                        showAddDialog = false
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
        )
    }

    editing?.let { current ->
        TtsRuleEditDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { draft ->
                scope.launch {
                    try {
                        repository.ttsRules.update(draft)
                        editing = null
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                    }
                }
            },
        )
    }

    JConfirmDialog(
        visible = toDelete != null,
        title = "Smazat pravidlo?",
        message = toDelete?.let { ttsRuleLabel(it) } ?: "",
        confirmText = "Smazat",
        isDestructive = true,
        onConfirm = {
            val rule = toDelete ?: return@JConfirmDialog
            scope.launch {
                try {
                    repository.ttsRules.delete(rule.id)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                }
                toDelete = null
            }
        },
        onDismiss = { toDelete = null },
    )

    if (showPreview) {
        TtsRulePreviewDialog(
            repository = repository,
            onDismiss = { showPreview = false },
        )
    }
}

@Composable
private fun TtsRuleRow(
    rule: TtsRuleDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    JCard(onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ttsRuleLabel(rule),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = ttsRuleSubtitle(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(JervisSpacing.touchTarget),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Smazat")
            }
        }
    }
}

@Composable
private fun TtsRuleEditDialog(
    initial: TtsRuleDto?,
    onDismiss: () -> Unit,
    onSave: (TtsRuleDto) -> Unit,
) {
    val editing = initial != null
    var type by remember { mutableStateOf(initial?.type ?: TtsRuleTypeDto.ACRONYM) }
    var language by remember { mutableStateOf(initial?.language ?: "cs") }
    var scopeType by remember { mutableStateOf(initial?.scope?.type ?: TtsRuleScopeTypeDto.GLOBAL) }
    var scopeClientId by remember { mutableStateOf(initial?.scope?.clientId.orEmpty()) }
    var scopeProjectId by remember { mutableStateOf(initial?.scope?.projectId.orEmpty()) }
    var acronym by remember { mutableStateOf(initial?.acronym.orEmpty()) }
    var pronunciation by remember { mutableStateOf(initial?.pronunciation.orEmpty()) }
    var aliases by remember { mutableStateOf(initial?.aliases?.joinToString(", ").orEmpty()) }
    var pattern by remember { mutableStateOf(initial?.pattern.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var stripParens by remember { mutableStateOf(initial?.stripWrappingParens ?: false) }
    var replacement by remember { mutableStateOf(initial?.replacement.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Upravit pravidlo" else "Přidat pravidlo") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                JDropdown(
                    items = TtsRuleTypeDto.entries,
                    selectedItem = type,
                    onItemSelected = { type = it },
                    label = "Typ",
                    itemLabel = { ttsRuleTypeLabel(it) },
                    enabled = !editing,
                )
                JDropdown(
                    items = listOf("cs", "en", "any"),
                    selectedItem = language,
                    onItemSelected = { language = it },
                    label = "Jazyk",
                    itemLabel = { it },
                )
                JDropdown(
                    items = TtsRuleScopeTypeDto.entries,
                    selectedItem = scopeType,
                    onItemSelected = { scopeType = it },
                    label = "Rozsah",
                    itemLabel = { ttsRuleScopeLabel(it) },
                )
                if (scopeType == TtsRuleScopeTypeDto.CLIENT) {
                    JTextField(value = scopeClientId, onValueChange = { scopeClientId = it }, label = "ID klienta")
                }
                if (scopeType == TtsRuleScopeTypeDto.PROJECT) {
                    JTextField(value = scopeProjectId, onValueChange = { scopeProjectId = it }, label = "ID projektu")
                }
                when (type) {
                    TtsRuleTypeDto.ACRONYM -> {
                        JTextField(value = acronym, onValueChange = { acronym = it }, label = "Zkratka (BMS)")
                        JTextField(value = pronunciation, onValueChange = { pronunciation = it }, label = "Výslovnost (bé-em-es)")
                        JTextField(value = aliases, onValueChange = { aliases = it }, label = "Aliasy (oddělené čárkou)")
                    }
                    TtsRuleTypeDto.STRIP -> {
                        JTextField(value = pattern, onValueChange = { pattern = it }, label = "Regex")
                        JTextField(value = description, onValueChange = { description = it }, label = "Popis")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = stripParens, onCheckedChange = { stripParens = it })
                            Text("Smazat i obklopující závorky ()")
                        }
                    }
                    TtsRuleTypeDto.REPLACE -> {
                        JTextField(value = pattern, onValueChange = { pattern = it }, label = "Regex")
                        JTextField(value = replacement, onValueChange = { replacement = it }, label = "Náhrada")
                        JTextField(value = description, onValueChange = { description = it }, label = "Popis")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val draft = TtsRuleDto(
                    id = initial?.id.orEmpty(),
                    type = type,
                    language = language,
                    scope = TtsRuleScopeDto(
                        type = scopeType,
                        clientId = scopeClientId.takeIf { it.isNotBlank() && scopeType == TtsRuleScopeTypeDto.CLIENT },
                        projectId = scopeProjectId.takeIf { it.isNotBlank() && scopeType == TtsRuleScopeTypeDto.PROJECT },
                    ),
                    acronym = acronym.takeIf { it.isNotBlank() && type == TtsRuleTypeDto.ACRONYM },
                    pronunciation = pronunciation.takeIf { it.isNotBlank() && type == TtsRuleTypeDto.ACRONYM },
                    aliases = aliases.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() && type == TtsRuleTypeDto.ACRONYM }
                        ?: emptyList(),
                    pattern = pattern.takeIf { it.isNotBlank() && type != TtsRuleTypeDto.ACRONYM },
                    description = description.takeIf { it.isNotBlank() && type != TtsRuleTypeDto.ACRONYM },
                    stripWrappingParens = if (type == TtsRuleTypeDto.STRIP) stripParens else null,
                    replacement = replacement.takeIf { type == TtsRuleTypeDto.REPLACE },
                )
                onSave(draft)
            }) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@Composable
private fun TtsRulePreviewDialog(
    repository: JervisRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var hitsCount by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Náhled normalizace") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap),
            ) {
                JTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Vstupní text",
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    singleLine = false,
                )
                val currentOutput = output
                if (currentOutput != null) {
                    Text("Výstup ($hitsCount pravidel zasaženo):", style = MaterialTheme.typography.labelMedium)
                    Text(currentOutput, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !running,
                onClick = {
                    scope.launch {
                        running = true
                        try {
                            val preview = repository.ttsRules.preview(
                                TtsRulePreviewRequestDto(text = input, language = "any"),
                            )
                            output = preview.output
                            hitsCount = preview.hits.size
                        } finally {
                            running = false
                        }
                    }
                },
            ) { Text("Spustit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

private fun ttsRuleTypeLabel(type: TtsRuleTypeDto): String = when (type) {
    TtsRuleTypeDto.ACRONYM -> "Zkratky"
    TtsRuleTypeDto.STRIP -> "Mazání"
    TtsRuleTypeDto.REPLACE -> "Nahrazení"
}

private fun ttsRuleScopeLabel(scope: TtsRuleScopeTypeDto): String = when (scope) {
    TtsRuleScopeTypeDto.GLOBAL -> "Globální"
    TtsRuleScopeTypeDto.CLIENT -> "Klient"
    TtsRuleScopeTypeDto.PROJECT -> "Projekt"
}

private fun ttsRuleLabel(rule: TtsRuleDto): String = when (rule.type) {
    TtsRuleTypeDto.ACRONYM -> "${rule.acronym ?: "?"} → ${rule.pronunciation ?: "?"}"
    TtsRuleTypeDto.STRIP -> rule.description ?: (rule.pattern ?: "?")
    TtsRuleTypeDto.REPLACE -> "${rule.description ?: rule.pattern ?: "?"} → ${rule.replacement ?: "?"}"
}

private fun ttsRuleSubtitle(rule: TtsRuleDto): String {
    val scope = ttsRuleScopeLabel(rule.scope.type)
    val lang = rule.language
    return "$scope · $lang"
}
