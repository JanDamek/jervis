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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.jervis.dto.whisper.WhisperModelSize
import com.jervis.dto.whisper.WhisperSettingsDto
import com.jervis.dto.whisper.WhisperSettingsUpdateDto
import com.jervis.dto.whisper.WhisperTask
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSlider
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JSwitch
import com.jervis.ui.design.JTextField
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
            else -> SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // === Model & Task Section ===
                    JSection(title = "Model a úloha") {
                        JDropdown(
                            items = WhisperModelSize.entries.toList(),
                            selectedItem = model,
                            onItemSelected = { model = it },
                            label = "Model",
                            itemLabel = { "${it.displayName} — ${it.description}" },
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JDropdown(
                            items = WhisperTask.entries.toList(),
                            selectedItem = task,
                            onItemSelected = { task = it },
                            label = "Úloha",
                            itemLabel = { it.displayName },
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JTextField(
                            value = language,
                            onValueChange = { language = it },
                            label = "Jazyk",
                            placeholder = "Prázdné = automatická detekce",
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ISO 639-1 kódy: cs (čeština), en (angličtina), de (němčina), sk (slovenština)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // === Quality Section ===
                    JSection(title = "Kvalita přepisu") {
                        JSlider(
                            label = "Beam size",
                            value = beamSize,
                            onValueChange = { beamSize = it },
                            valueRange = 1f..10f,
                            steps = 8,
                            valueLabel = "${beamSize.roundToInt()}",
                            description = "Vyšší = přesnější, ale pomalejší",
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JSwitch(
                            label = "VAD filtr (detekce ticha)",
                            checked = vadFilter,
                            onCheckedChange = { vadFilter = it },
                            description = "Přeskočí tiché úseky — výrazně zrychlí zpracování",
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JSwitch(
                            label = "Časování po slovech",
                            checked = wordTimestamps,
                            onCheckedChange = { wordTimestamps = it },
                            description = "Přesné časové značky pro každé slovo",
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JSwitch(
                            label = "Kontextové navazování",
                            checked = conditionOnPreviousText,
                            onCheckedChange = { conditionOnPreviousText = it },
                            description = "Používá předchozí text jako kontext. Vypněte pro dlouhé nahrávky.",
                        )
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JSlider(
                            label = "Práh detekce řeči",
                            value = noSpeechThreshold,
                            onValueChange = { noSpeechThreshold = it },
                            valueRange = 0f..1f,
                            steps = 9,
                            valueLabel = "${ (noSpeechThreshold * 10).toInt() / 10.0 }",
                            description = "Segmenty s pravděpodobností ticha nad tímto prahem budou přeskočeny",
                        )
                    }

                    // === Performance Section ===
                    JSection(title = "Výkon") {
                        JSlider(
                            label = "Max paralelních jobů",
                            value = maxParallelJobs,
                            onValueChange = { maxParallelJobs = it },
                            valueRange = 1f..10f,
                            steps = 8,
                            valueLabel = "${maxParallelJobs.roundToInt()}",
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
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}
