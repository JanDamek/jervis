package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.jervis.dto.whisper.WhisperModelSize
import com.jervis.dto.whisper.WhisperSettingsDto
import com.jervis.dto.whisper.WhisperSettingsUpdateDto
import com.jervis.dto.whisper.WhisperTask
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Whisper transcription settings screen.
 * Allows configuration of model, language, quality parameters, and concurrency limits.
 */
@Composable
fun WhisperSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf<WhisperSettingsDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Editable state
    var model by remember { mutableStateOf(WhisperModelSize.BASE) }
    var task by remember { mutableStateOf(WhisperTask.TRANSCRIBE) }
    var language by remember { mutableStateOf("") }
    var beamSize by remember { mutableStateOf(5f) }
    var vadFilter by remember { mutableStateOf(true) }
    var wordTimestamps by remember { mutableStateOf(false) }
    var conditionOnPreviousText by remember { mutableStateOf(true) }
    var noSpeechThreshold by remember { mutableStateOf(0.6f) }
    var maxParallelJobs by remember { mutableStateOf(3f) }

    fun applyFromDto(dto: WhisperSettingsDto) {
        model = dto.model
        task = dto.task
        language = dto.language ?: ""
        beamSize = dto.beamSize.toFloat()
        vadFilter = dto.vadFilter
        wordTimestamps = dto.wordTimestamps
        conditionOnPreviousText = dto.conditionOnPreviousText
        noSpeechThreshold = dto.noSpeechThreshold.toFloat()
        maxParallelJobs = dto.maxParallelJobs.toFloat()
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val dto = repository.whisperSettings.getSettings()
            settings = dto
            applyFromDto(dto)
            error = null
        } catch (e: Exception) {
            error = "Chyba načítání: ${e.message}"
        }
        isLoading = false
    }

    fun saveSettings() {
        scope.launch {
            try {
                val updated = repository.whisperSettings.updateSettings(
                    WhisperSettingsUpdateDto(
                        model = model,
                        task = task,
                        language = language.ifBlank { null },
                        clearLanguage = language.isBlank(),
                        beamSize = beamSize.roundToInt(),
                        vadFilter = vadFilter,
                        wordTimestamps = wordTimestamps,
                        conditionOnPreviousText = conditionOnPreviousText,
                        noSpeechThreshold = noSpeechThreshold.toDouble(),
                        maxParallelJobs = maxParallelJobs.roundToInt(),
                    ),
                )
                settings = updated
                applyFromDto(updated)
                snackbarHostState.showSnackbar("Nastavení uloženo")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(
                message = error!!,
                onRetry = {
                    scope.launch {
                        isLoading = true
                        try {
                            val dto = repository.whisperSettings.getSettings()
                            settings = dto
                            applyFromDto(dto)
                            error = null
                        } catch (e: Exception) {
                            error = "Chyba načítání: ${e.message}"
                        }
                        isLoading = false
                    }
                },
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // === Model & Task Section ===
                JSection(title = "Model a úloha") {
                    ModelDropdown(
                        selected = model,
                        onSelect = { model = it },
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    TaskDropdown(
                        selected = task,
                        onSelect = { task = it },
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    LanguageField(
                        value = language,
                        onValueChange = { language = it },
                    )
                }

                // === Quality Section ===
                JSection(title = "Kvalita přepisu") {
                    SliderSetting(
                        label = "Beam size",
                        value = beamSize,
                        onValueChange = { beamSize = it },
                        valueRange = 1f..10f,
                        steps = 8,
                        valueLabel = { "${it.roundToInt()}" },
                        description = "Vyšší = přesnější, ale pomalejší",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    SwitchSetting(
                        label = "VAD filtr (detekce ticha)",
                        checked = vadFilter,
                        onCheckedChange = { vadFilter = it },
                        description = "Přeskočí tiché úseky — výrazně zrychlí zpracování",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    SwitchSetting(
                        label = "Časování po slovech",
                        checked = wordTimestamps,
                        onCheckedChange = { wordTimestamps = it },
                        description = "Přesné časové značky pro každé slovo",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    SwitchSetting(
                        label = "Kontextové navazování",
                        checked = conditionOnPreviousText,
                        onCheckedChange = { conditionOnPreviousText = it },
                        description = "Používá předchozí text jako kontext. Vypněte pro dlouhé nahrávky.",
                    )
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    SliderSetting(
                        label = "Práh detekce řeči",
                        value = noSpeechThreshold,
                        onValueChange = { noSpeechThreshold = it },
                        valueRange = 0f..1f,
                        steps = 9,
                        valueLabel = { val rounded = (it * 10).toInt() / 10.0; "$rounded" },
                        description = "Segmenty s pravděpodobností ticha nad tímto prahem budou přeskočeny",
                    )
                }

                // === Performance Section ===
                JSection(title = "Výkon") {
                    SliderSetting(
                        label = "Max paralelních jobů",
                        value = maxParallelJobs,
                        onValueChange = { maxParallelJobs = it },
                        valueRange = 1f..10f,
                        steps = 8,
                        valueLabel = { "${it.roundToInt()}" },
                        description = "Kolik nahrávek se přepisuje současně",
                    )
                }

                // === Save Button ===
                Row(
                    modifier = Modifier.fillMaxWidth().padding(JervisSpacing.outerPadding),
                    horizontalArrangement = Arrangement.End,
                ) {
                    JPrimaryButton(onClick = { saveSettings() }) {
                        Text("Uložit nastavení")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

// ===== Reusable Setting Components =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selected: WhisperModelSize,
    onSelect: (WhisperModelSize) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Model", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = "${selected.displayName} — ${selected.description}",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                WhisperModelSize.entries.forEach { modelSize ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(modelSize.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    modelSize.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(modelSize)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDropdown(
    selected: WhisperTask,
    onSelect: (WhisperTask) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Úloha", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                WhisperTask.entries.forEach { taskOption ->
                    DropdownMenuItem(
                        text = { Text(taskOption.displayName) },
                        onClick = {
                            onSelect(taskOption)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text("Jazyk", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Kód jazyka") },
            placeholder = { Text("Prázdné = automatická detekce") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ISO 639-1 kódy: cs (čeština), en (angličtina), de (němčina), sk (slovenština)...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    description: String,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                valueLabel(value),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(JervisSpacing.touchTarget),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
