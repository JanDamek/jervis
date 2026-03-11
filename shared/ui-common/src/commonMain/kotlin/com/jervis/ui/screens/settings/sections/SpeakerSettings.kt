package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AssistChip
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
import com.jervis.dto.ClientDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JConfirmDialog
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
internal fun SpeakerSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var speakers by remember { mutableStateOf<List<SpeakerDto>>(emptyList()) }
    var selectedSpeaker by remember { mutableStateOf<SpeakerDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadSpeakers() {
        val clientId = selectedClient?.id ?: return
        scope.launch {
            isLoading = true
            try {
                speakers = repository.speakers.listSpeakers(clientId)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            clients = repository.clients.getAllClients()
            if (clients.size == 1) {
                selectedClient = clients.first()
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(selectedClient) {
        selectedSpeaker = null
        if (selectedClient != null) loadSpeakers()
    }

    Column {
        // Client selector
        JDropdown(
            items = clients,
            selectedItem = selectedClient,
            onItemSelected = { selectedClient = it },
            label = "Klient",
            itemLabel = { it.name },
            placeholder = "Vyberte klienta",
            modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
        )

        Spacer(Modifier.height(8.dp))

        if (selectedClient != null) {
            JListDetailLayout(
                items = speakers,
                selectedItem = selectedSpeaker,
                isLoading = isLoading,
                onItemSelected = { selectedSpeaker = it },
                emptyMessage = "Žádní řečníci — vznikají automaticky z přepisu meetingů",
                emptyIcon = "\uD83C\uDFA4",
                listHeader = {
                    JActionBar {
                        RefreshIconButton(onClick = { loadSpeakers() })
                    }
                },
                listItem = { speaker ->
                    SpeakerListCard(speaker = speaker, onClick = { selectedSpeaker = speaker })
                },
                detailContent = { speaker ->
                    SpeakerEditForm(
                        speaker = speaker,
                        onSave = { update ->
                            scope.launch {
                                try {
                                    repository.speakers.updateSpeaker(update)
                                    selectedSpeaker = null
                                    loadSpeakers()
                                    snackbarHostState.showSnackbar("Řečník uložen")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    repository.speakers.deleteSpeaker(speaker.id)
                                    selectedSpeaker = null
                                    loadSpeakers()
                                    snackbarHostState.showSnackbar("Řečník smazán")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        },
                        onCancel = { selectedSpeaker = null },
                    )
                },
            )
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun SpeakerListCard(speaker: SpeakerDto, onClick: () -> Unit) {
    JCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(speaker.name, style = MaterialTheme.typography.titleMedium)
                val details = buildList {
                    if (!speaker.nationality.isNullOrBlank()) add(speaker.nationality)
                    if (speaker.languagesSpoken.isNotEmpty()) add(speaker.languagesSpoken.joinToString(", "))
                }
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" \u2022 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (speaker.hasVoiceprint) {
                    Text(
                        "Hlasový otisk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeakerEditForm(
    speaker: SpeakerDto,
    onSave: (SpeakerUpdateDto) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(speaker.id) { mutableStateOf(speaker.name) }
    var nationality by remember(speaker.id) { mutableStateOf(speaker.nationality ?: "") }
    var languages by remember(speaker.id) { mutableStateOf(speaker.languagesSpoken.joinToString(", ")) }
    var notes by remember(speaker.id) { mutableStateOf(speaker.notes ?: "") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    JDetailScreen(
        title = speaker.name,
        onBack = onCancel,
        onSave = {
            onSave(
                SpeakerUpdateDto(
                    id = speaker.id,
                    name = name.trim(),
                    nationality = nationality.trim().ifBlank { null },
                    languagesSpoken = languages.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    notes = notes.trim().ifBlank { null },
                ),
            )
        },
        saveEnabled = name.isNotBlank(),
        actions = {
            JDestructiveButton(onClick = { showDeleteDialog = true }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Smazat")
            }
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JSection(title = "Základní údaje") {
                JTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Jméno",
                    modifier = Modifier.fillMaxWidth(),
                )
                JTextField(
                    value = nationality,
                    onValueChange = { nationality = it },
                    label = "Národnost",
                    placeholder = "např. Čech, Slovák, Němec",
                    modifier = Modifier.fillMaxWidth(),
                )
                JTextField(
                    value = languages,
                    onValueChange = { languages = it },
                    label = "Jazyky (oddělené čárkou)",
                    placeholder = "např. cs, sk, en",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            JSection(title = "Poznámky pro LLM") {
                JTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Poznámky",
                    placeholder = "Specifika řeči, zaměření, kontext pro korekci transkripce",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                )
            }

            JSection(title = "Hlasový profil (${speaker.voiceprintCount} otisků)") {
                if (speaker.hasVoiceprint) {
                    speaker.voiceprintLabels.forEach { label ->
                        AssistChip(
                            onClick = {},
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                } else {
                    Text(
                        "Hlasový otisk zatím nebyl uložen. Přiřaďte řečníka v meetingu s diarizací.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    JConfirmDialog(
        visible = showDeleteDialog,
        title = "Smazat řečníka",
        message = "Opravdu chcete smazat řečníka \"${speaker.name}\"? Tato akce je nevratná.",
        confirmText = "Smazat",
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        isDestructive = true,
    )
}


