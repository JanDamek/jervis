package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.meeting.SpeakerChannelDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerMergeRequestDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JConfirmDialog
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

private val CHAT_PROVIDERS = setOf(
    ProviderEnum.MICROSOFT_TEAMS,
    ProviderEnum.SLACK,
    ProviderEnum.DISCORD,
)

@Composable
internal fun SpeakerSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    var filterClient by remember { mutableStateOf<ClientDto?>(null) }
    var speakers by remember { mutableStateOf<List<SpeakerDto>>(emptyList()) }
    var selectedSpeaker by remember { mutableStateOf<SpeakerDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var mergingSpeaker by remember { mutableStateOf<SpeakerDto?>(null) }

    fun loadSpeakers() {
        scope.launch {
            isLoading = true
            try {
                speakers = if (filterClient != null) {
                    repository.speakers.listSpeakers(filterClient!!.id)
                } else {
                    repository.speakers.listAllSpeakers()
                }
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
            connections = repository.connections.getAllConnections()
        } catch (_: Exception) {}
        loadSpeakers()
    }

    LaunchedEffect(filterClient) {
        selectedSpeaker = null
        loadSpeakers()
    }

    val chatConnections = connections.filter { it.provider in CHAT_PROVIDERS }

    Column {
        JDropdown(
            items = listOf(null) + clients,
            selectedItem = filterClient,
            onItemSelected = { filterClient = it },
            label = "Filtr klienta",
            itemLabel = { it?.name ?: "Všichni řečníci" },
            placeholder = "Všichni řečníci",
            modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
        )

        Spacer(Modifier.height(8.dp))

        JListDetailLayout(
            items = speakers,
            selectedItem = selectedSpeaker,
            isLoading = isLoading,
            onItemSelected = { selectedSpeaker = it },
            emptyMessage = "Žádní řečníci",
            emptyIcon = "\uD83C\uDFA4",
            listHeader = {
                JActionBar {
                    RefreshIconButton(onClick = { loadSpeakers() })
                }
            },
            listItem = { speaker ->
                SpeakerListCard(
                    speaker = speaker,
                    clients = clients,
                    connections = chatConnections,
                    onClick = { selectedSpeaker = speaker },
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
                    onMerge = { mergingSpeaker = speaker },
                )
            },
            detailContent = { speaker ->
                SpeakerEditForm(
                    speaker = speaker,
                    clients = clients,
                    chatConnections = chatConnections,
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
                    onCancel = { selectedSpeaker = null },
                )
            },
        )

        JSnackbarHost(snackbarHostState)
    }

    if (mergingSpeaker != null) {
        SpeakerMergeDialog(
            source = mergingSpeaker!!,
            allSpeakers = speakers,
            repository = repository,
            snackbarHostState = snackbarHostState,
            onDismiss = { mergingSpeaker = null },
            onMerged = {
                mergingSpeaker = null
                selectedSpeaker = null
                loadSpeakers()
            },
        )
    }
}

@Composable
private fun SpeakerListCard(
    speaker: SpeakerDto,
    clients: List<ClientDto>,
    connections: List<ConnectionResponseDto>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val clientNames = clients.filter { it.id in speaker.clientIds }.map { it.name }

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
                if (clientNames.isNotEmpty()) {
                    Text(
                        clientNames.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val badges = buildList {
                    if (speaker.hasVoiceprint) add("Hlasový otisk")
                    if (speaker.emails.isNotEmpty()) add("${speaker.emails.size} email${if (speaker.emails.size > 1) "ů" else ""}")
                    if (speaker.channels.isNotEmpty()) {
                        val channelSummary = speaker.channels
                            .mapNotNull { ch -> connections.find { it.id == ch.connectionId }?.name }
                            .distinct()
                        if (channelSummary.isNotEmpty()) add(channelSummary.joinToString(", "))
                    }
                }
                if (badges.isNotEmpty()) {
                    Text(
                        badges.joinToString(" \u2022 "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(JervisSpacing.touchTarget),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Akce")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Sloučit") },
                    onClick = { showMenu = false; onMerge() },
                )
                DropdownMenuItem(
                    text = { Text("Smazat", color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; showDeleteDialog = true },
                )
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

@Composable
private fun SpeakerMergeDialog(
    source: SpeakerDto,
    allSpeakers: List<SpeakerDto>,
    repository: JervisRepository,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onMerged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val otherSpeakers = allSpeakers.filter { it.id != source.id }
    var selectedTarget by remember { mutableStateOf<SpeakerDto?>(null) }
    var similarity by remember { mutableStateOf<Float?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showLowSimilarityConfirm by remember { mutableStateOf(false) }
    var isMerging by remember { mutableStateOf(false) }

    fun doMerge(target: SpeakerDto) {
        scope.launch {
            isMerging = true
            try {
                repository.speakers.mergeSpeakers(
                    SpeakerMergeRequestDto(
                        targetSpeakerId = target.id,
                        sourceSpeakerId = source.id,
                    ),
                )
                snackbarHostState.showSnackbar("Sloučeno: ${source.name} -> ${target.name}")
                onMerged()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                isMerging = false
            }
        }
    }

    fun checkAndMerge(target: SpeakerDto) {
        if (!source.hasVoiceprint || !target.hasVoiceprint) {
            doMerge(target)
            return
        }
        scope.launch {
            isChecking = true
            try {
                val result = repository.speakers.checkSimilarity(source.id, target.id)
                similarity = result.similarity
                if (result.similarity >= 0.75f) {
                    doMerge(target)
                } else {
                    showLowSimilarityConfirm = true
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            } finally {
                isChecking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sloučit řečníka") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Sloučit \"${source.name}\" do vybraného řečníka. " +
                        "Klienti, emaily, kanály a hlasové otisky budou spojeny.",
                    style = MaterialTheme.typography.bodySmall,
                )
                JDropdown(
                    items = otherSpeakers,
                    selectedItem = selectedTarget,
                    onItemSelected = {
                        selectedTarget = it
                        similarity = null
                    },
                    label = "Sloučit do",
                    itemLabel = { it.name },
                    placeholder = "Vyberte řečníka",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isChecking) {
                    Text(
                        "Ověřuji podobnost hlasových otisků...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isMerging) {
                    Text(
                        "Slučuji...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = selectedTarget ?: return@TextButton
                    checkAndMerge(target)
                },
                enabled = selectedTarget != null && !isChecking && !isMerging,
            ) {
                Text("Sloučit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        },
    )

    val simPercent = ((similarity ?: 0f) * 100).toInt()
    JConfirmDialog(
        visible = showLowSimilarityConfirm,
        title = "Nízká podobnost hlasových otisků",
        message = "Podobnost hlasových otisků je pouze ${simPercent}%. Opravdu chcete tyto řečníky sloučit?",
        confirmText = "Přesto sloučit",
        onConfirm = {
            showLowSimilarityConfirm = false
            val target = selectedTarget ?: return@JConfirmDialog
            doMerge(target)
        },
        onDismiss = { showLowSimilarityConfirm = false },
        isDestructive = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerEditForm(
    speaker: SpeakerDto,
    clients: List<ClientDto>,
    chatConnections: List<ConnectionResponseDto>,
    onSave: (SpeakerUpdateDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(speaker.id) { mutableStateOf(speaker.name) }
    var nationality by remember(speaker.id) { mutableStateOf(speaker.nationality ?: "") }
    var languages by remember(speaker.id) { mutableStateOf(speaker.languagesSpoken.joinToString(", ")) }
    var notes by remember(speaker.id) { mutableStateOf(speaker.notes ?: "") }
    var emails by remember(speaker.id) { mutableStateOf(speaker.emails) }
    var newEmail by remember(speaker.id) { mutableStateOf("") }
    var channels by remember(speaker.id) { mutableStateOf(speaker.channels) }
    var newChannelConnection by remember(speaker.id) { mutableStateOf<ConnectionResponseDto?>(null) }
    var newChannelId by remember(speaker.id) { mutableStateOf("") }
    var newChannelName by remember(speaker.id) { mutableStateOf("") }
    var selectedClientIds by remember(speaker.id) { mutableStateOf(speaker.clientIds.toSet()) }

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
                    clientIds = selectedClientIds.toList(),
                    emails = emails,
                    channels = channels,
                ),
            )
        },
        saveEnabled = name.isNotBlank(),
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

            JSection(title = "Klienti") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    clients.forEach { client ->
                        FilterChip(
                            selected = client.id in selectedClientIds,
                            onClick = {
                                selectedClientIds = if (client.id in selectedClientIds) {
                                    selectedClientIds - client.id
                                } else {
                                    selectedClientIds + client.id
                                }
                            },
                            label = { Text(client.name) },
                        )
                    }
                }
            }

            JSection(title = "Emaily") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    emails.forEach { email ->
                        InputChip(
                            selected = false,
                            onClick = { emails = emails - email },
                            label = { Text(email) },
                            leadingIcon = {
                                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Odebrat", modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    JTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = "Nový email",
                        placeholder = "user@example.com",
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newEmail.trim()
                            if (trimmed.isNotBlank() && trimmed !in emails) {
                                emails = emails + trimmed
                                newEmail = ""
                            }
                        },
                        enabled = newEmail.trim().isNotBlank(),
                        modifier = Modifier.size(JervisSpacing.touchTarget),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Přidat email")
                    }
                }
            }

            JSection(title = "Komunikační kanály") {
                channels.forEachIndexed { index, channel ->
                    val conn = chatConnections.find { it.id == channel.connectionId }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    buildString {
                                        append(conn?.name ?: channel.connectionId)
                                        append(": ")
                                        append(channel.displayName ?: channel.identifier)
                                    },
                                )
                            },
                        )
                        IconButton(
                            onClick = { channels = channels.toMutableList().also { it.removeAt(index) } },
                            modifier = Modifier.size(JervisSpacing.touchTarget),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Odebrat kanál", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (chatConnections.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        JDropdown(
                            items = chatConnections,
                            selectedItem = newChannelConnection,
                            onItemSelected = { newChannelConnection = it },
                            label = "Spojení",
                            itemLabel = { "${it.name} (${it.provider.name})" },
                            placeholder = "Vyberte spojení",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        JTextField(
                            value = newChannelId,
                            onValueChange = { newChannelId = it },
                            label = "ID / handle uživatele",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        JTextField(
                            value = newChannelName,
                            onValueChange = { newChannelName = it },
                            label = "Zobrazovaný název (volitelné)",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val conn = newChannelConnection ?: return@IconButton
                                val trimmedId = newChannelId.trim()
                                if (trimmedId.isNotBlank()) {
                                    channels = channels + SpeakerChannelDto(
                                        connectionId = conn.id,
                                        identifier = trimmedId,
                                        displayName = newChannelName.trim().ifBlank { null },
                                    )
                                    newChannelId = ""
                                    newChannelName = ""
                                    newChannelConnection = null
                                }
                            },
                            enabled = newChannelConnection != null && newChannelId.trim().isNotBlank(),
                            modifier = Modifier.size(JervisSpacing.touchTarget),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Přidat kanál")
                        }
                    }
                } else {
                    Text(
                        "Nejsou k dispozici žádná spojení typu Teams, Slack nebo Discord.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
}
