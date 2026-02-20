package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.design.JAddButton
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.storage.RecordingState
import com.jervis.ui.util.ConfirmDialog


/**
 * Meetings screen - list of recordings with detail view.
 * Uses global client/project selection from PersistentTopBar.
 */
@Composable
fun MeetingsScreen(
    viewModel: MeetingViewModel,
    clients: List<ClientDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    onBack: () -> Unit,
    offlineSyncService: OfflineMeetingSyncService? = null,
) {
    val meetings by viewModel.meetings.collectAsState()
    val vmProjects by viewModel.projects.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val selectedMeeting by viewModel.selectedMeeting.collectAsState()
    val currentMeetingId by viewModel.currentMeetingId.collectAsState()
    val playingMeetingId by viewModel.playingMeetingId.collectAsState()
    val playingSegmentIndex by viewModel.playingSegmentIndex.collectAsState()
    val isCorrecting by viewModel.isCorrecting.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val transcriptionProgress by viewModel.transcriptionProgress.collectAsState()
    val transcriptionLastSegment by viewModel.transcriptionLastSegment.collectAsState()
    val correctionProgress by viewModel.correctionProgress.collectAsState()
    val pendingChatMessage by viewModel.pendingChatMessage.collectAsState()
    val error by viewModel.error.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()

    val deletedMeetings by viewModel.deletedMeetings.collectAsState()
    val unclassifiedMeetings by viewModel.unclassifiedMeetings.collectAsState()
    val offlinePending = offlineSyncService?.pendingMeetings?.collectAsState()?.value ?: emptyList()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var classifyTarget by remember { mutableStateOf<MeetingDto?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPermanentDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var interruptedRecording by remember { mutableStateOf<RecordingState?>(null) }
    val audioRecorder = remember { AudioRecorder() }

    // Check for interrupted recording on startup
    LaunchedEffect(Unit) {
        val state = viewModel.checkForInterruptedRecording()
        if (state != null) {
            interruptedRecording = state
        }
    }

    // Load unclassified meetings once on startup
    LaunchedEffect(Unit) {
        viewModel.loadUnclassifiedMeetings()
    }

    // Load meetings + projects when global selection changes
    LaunchedEffect(selectedClientId, selectedProjectId, showTrash) {
        selectedClientId?.let { clientId ->
            if (showTrash) {
                viewModel.loadDeletedMeetings(clientId, selectedProjectId)
            } else {
                viewModel.loadMeetings(clientId, selectedProjectId)
            }
            viewModel.loadProjects(clientId)
        }
    }

    // Subscribe to real-time events
    LaunchedEffect(selectedClientId) {
        selectedClientId?.let { viewModel.subscribeToEvents(it) }
    }

    // Corrections sub-view state
    var showCorrections by remember { mutableStateOf(false) }

    // Detail view
    val currentDetail = selectedMeeting
    if (currentDetail != null) {
        val detailClientId = currentDetail.clientId
        if (showCorrections && detailClientId != null) {
            CorrectionsScreen(
                correctionService = viewModel.repository.transcriptCorrections,
                clientId = detailClientId,
                projectId = currentDetail.projectId,
                onBack = { showCorrections = false },
            )
            return
        }

        MeetingDetailView(
            meeting = currentDetail,
            isPlaying = playingMeetingId == currentDetail.id,
            playingSegmentIndex = if (playingMeetingId == currentDetail.id) playingSegmentIndex else -1,
            isCorrecting = isCorrecting,
            pendingChatMessage = pendingChatMessage,
            transcriptionPercent = transcriptionProgress[currentDetail.id],
            transcriptionLastSegment = transcriptionLastSegment[currentDetail.id],
            correctionProgress = correctionProgress[currentDetail.id],
            errorMessage = error,
            onDismissViewError = { viewModel.clearError() },
            onBack = { viewModel.selectMeeting(null) },
            onDelete = { showDeleteConfirmDialog = currentDetail.id },
            onRefresh = { viewModel.refreshMeeting(currentDetail.id) },
            onPlayToggle = { viewModel.playAudio(currentDetail.id) },
            onRetranscribe = { viewModel.retranscribeMeeting(currentDetail.id) },
            onRecorrect = { viewModel.recorrectMeeting(currentDetail.id) },
            onReindex = { viewModel.reindexMeeting(currentDetail.id) },
            onCorrections = { showCorrections = true },
            onAnswerQuestions = { answers -> viewModel.answerQuestions(currentDetail.id, answers) },
            onApplySegmentCorrection = { segmentIndex, correctedText ->
                viewModel.applySegmentCorrection(currentDetail.id, segmentIndex, correctedText)
            },
            onCorrectWithInstruction = { instruction ->
                viewModel.correctWithInstruction(currentDetail.id, instruction)
            },
            onSegmentPlay = { segmentIndex, startSec, endSec ->
                viewModel.playSegment(currentDetail.id, segmentIndex, startSec, endSec)
            },
            onDismissError = { viewModel.dismissMeetingError(currentDetail.id) },
            onRetranscribeSegment = { segmentIndex ->
                viewModel.retranscribeSegment(currentDetail.id, segmentIndex)
            },
            onStopTranscription = { viewModel.stopTranscription(currentDetail.id) },
        )
        return
    }

    // Filter out the currently recording meeting from the list
    val displayedMeetings = if (currentMeetingId != null) {
        meetings.filter { it.id != currentMeetingId }
    } else {
        meetings
    }

    // List view
    Scaffold(
        topBar = {
            JTopBar(
                title = "Meetingy",
                actions = {
                    JAddButton(
                        onClick = { showSetupDialog = true },
                        enabled = !isRecording,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Trash / Active toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showTrash,
                    onClick = { showTrash = false },
                    label = { Text("Nahrávky") },
                )
                FilterChip(
                    selected = showTrash,
                    onClick = { showTrash = true },
                    label = { Text("Koš") },
                )
            }

            // Saving indicator (after stop, during upload + finalization)
            if (isSaving && !showTrash) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Ukládám nahrávku...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Error display
            error?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // Content
            if (showTrash) {
                // Trash view
                when {
                    selectedClientId == null -> {
                        JEmptyState(message = "Vyberte klienta", icon = "")
                    }
                    isLoading -> {
                        JCenteredLoading()
                    }
                    deletedMeetings.isEmpty() -> {
                        JEmptyState(message = "Koš je prázdný", icon = "\uD83D\uDDD1")
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(deletedMeetings, key = { it.id }) { meeting ->
                                DeletedMeetingListItem(
                                    meeting = meeting,
                                    onRestore = { viewModel.restoreMeeting(meeting.id) },
                                    onPermanentDelete = { showPermanentDeleteConfirmDialog = meeting.id },
                                )
                            }
                        }
                    }
                }
            } else {
                // Active meetings view
                when {
                    selectedClientId == null -> {
                        JEmptyState(message = "Vyberte klienta", icon = "")
                    }
                    isLoading -> {
                        JCenteredLoading()
                    }
                    displayedMeetings.isEmpty() && !isRecording -> {
                        JEmptyState(message = "Zatím žádné nahrávky", icon = "")
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Unclassified meetings section
                            if (unclassifiedMeetings.isNotEmpty()) {
                                item(key = "unclassified_header") {
                                    Text(
                                        text = "Neklasifikované nahrávky",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                items(unclassifiedMeetings, key = { "unclassified_${it.id}" }) { meeting ->
                                    UnclassifiedMeetingListItem(
                                        meeting = meeting,
                                        isPlaying = playingMeetingId == meeting.id,
                                        onClassify = { classifyTarget = meeting },
                                        onClick = { viewModel.selectMeeting(meeting) },
                                        onPlayToggle = { viewModel.playAudio(meeting.id) },
                                    )
                                }
                                item(key = "unclassified_divider") {
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                            }

                            // Offline meetings section
                            if (offlinePending.isNotEmpty()) {
                                item(key = "offline_header") {
                                    Text(
                                        text = "Offline nahrávky — čekají na odeslání",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                items(offlinePending, key = { "offline_${it.localId}" }) { offline ->
                                    OfflineMeetingListItem(
                                        meeting = offline,
                                        onRetry = { offlineSyncService?.retryMeeting(offline.localId) },
                                    )
                                }
                                item(key = "offline_divider") {
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                            }

                            items(displayedMeetings, key = { it.id }) { meeting ->
                                MeetingListItem(
                                    meeting = meeting,
                                    isPlaying = playingMeetingId == meeting.id,
                                    transcriptionPercent = transcriptionProgress[meeting.id],
                                    correctionProgress = correctionProgress[meeting.id],
                                    onClick = { viewModel.selectMeeting(meeting) },
                                    onPlayToggle = { viewModel.playAudio(meeting.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Setup dialog — still has its own client/project selectors for choosing recording target
    if (showSetupDialog) {
        RecordingSetupDialog(
            clients = clients,
            projects = vmProjects,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            audioDevices = audioRecorder.getAvailableInputDevices(),
            systemAudioCapability = audioRecorder.getSystemAudioCapabilities(),
            onStart = { clientId, projectId, audioInputType, selectedDevice, title, meetingType ->
                showSetupDialog = false
                viewModel.startRecording(
                    clientId = clientId,
                    projectId = projectId,
                    audioInputType = audioInputType,
                    recordingConfig =
                        AudioRecordingConfig(
                            inputDevice = selectedDevice,
                            captureSystemAudio = audioInputType == AudioInputType.MIXED,
                        ),
                    title = title,
                    meetingType = meetingType,
                )
            },
            onDismiss = { showSetupDialog = false },
        )
    }

    // Delete confirmation dialog (soft delete -> trash)
    ConfirmDialog(
        visible = showDeleteConfirmDialog != null,
        title = "Smazat nahrávku?",
        message = "Nahrávka bude přesunuta do koše, kde zůstane 30 dní. Poté bude trvale smazána.",
        confirmText = "Smazat",
        onConfirm = {
            val meetingId = showDeleteConfirmDialog ?: return@ConfirmDialog
            showDeleteConfirmDialog = null
            viewModel.deleteMeeting(meetingId)
            viewModel.selectMeeting(null)
        },
        onDismiss = { showDeleteConfirmDialog = null },
    )

    // Permanent delete confirmation dialog
    ConfirmDialog(
        visible = showPermanentDeleteConfirmDialog != null,
        title = "Trvale smazat?",
        message = "Nahrávka bude trvale smazána včetně audio souboru. Tuto akci nelze vrátit zpět.",
        confirmText = "Trvale smazat",
        onConfirm = {
            val meetingId = showPermanentDeleteConfirmDialog ?: return@ConfirmDialog
            showPermanentDeleteConfirmDialog = null
            viewModel.permanentlyDeleteMeeting(meetingId)
        },
        onDismiss = { showPermanentDeleteConfirmDialog = null },
    )

    // Interrupted recording resume dialog
    ConfirmDialog(
        visible = interruptedRecording != null,
        title = "Nalezena přerušená nahrávka",
        message = buildString {
            append("Byla nalezena nedokončená nahrávka")
            interruptedRecording?.title?.let { append(" \"$it\"") }
            append(". Částečná data jsou uložena na serveru. Chcete nahrávku dokončit?")
        },
        confirmText = "Dokončit",
        onConfirm = {
            val state = interruptedRecording ?: return@ConfirmDialog
            interruptedRecording = null
            viewModel.resumeInterruptedUpload(state)
        },
        onDismiss = {
            val state = interruptedRecording ?: return@ConfirmDialog
            interruptedRecording = null
            viewModel.discardInterruptedRecording(state)
        },
        isDestructive = false,
        dismissText = "Zahodit",
    )

    // Classify meeting dialog
    classifyTarget?.let { meeting ->
        ClassifyMeetingDialog(
            meeting = meeting,
            clients = clients,
            projects = vmProjects,
            onLoadProjects = { clientId -> viewModel.loadProjects(clientId) },
            onClassify = { clientId, projectId, title, meetingType ->
                viewModel.classifyMeeting(
                    meetingId = meeting.id,
                    clientId = clientId,
                    projectId = projectId,
                    title = title,
                    meetingType = meetingType,
                )
                classifyTarget = null
            },
            onDismiss = { classifyTarget = null },
        )
    }
}

/**
 * List item for an unclassified meeting (ad-hoc recording without client/project).
 */
@Composable
private fun UnclassifiedMeetingListItem(
    meeting: MeetingDto,
    isPlaying: Boolean,
    onClassify: () -> Unit,
    onClick: () -> Unit,
    onPlayToggle: () -> Unit,
) {
    val durationText = meeting.durationSeconds?.let { dur ->
        "${dur / 60}:${(dur % 60).toString().padStart(2, '0')}"
    } ?: "—"

    val stateText = meeting.state.name.lowercase().replaceFirstChar { it.uppercase() }

    androidx.compose.material3.OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title ?: "Ad-hoc nahrávka",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "$stateText · $durationText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(onClick = onClassify) {
                Text("Klasifikovat")
            }
        }
    }
}

/**
 * Dialog for classifying an ad-hoc meeting — assign client, project, title, type.
 */
@Composable
private fun ClassifyMeetingDialog(
    meeting: MeetingDto,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onLoadProjects: (String) -> Unit,
    onClassify: (clientId: String, projectId: String?, title: String?, meetingType: MeetingTypeEnum?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(meeting.title ?: "") }
    var selectedMeetingType by remember { mutableStateOf<MeetingTypeEnum?>(meeting.meetingType) }

    // Load projects when client changes
    LaunchedEffect(selectedClientId) {
        selectedClientId?.let { onLoadProjects(it) }
    }

    val filteredProjects = projects.filter { it.clientId == selectedClientId }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Klasifikovat nahrávku") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Client selector
                Text("Klient", style = MaterialTheme.typography.labelMedium)
                Column {
                    clients.forEach { client ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedClientId == client.id,
                                onClick = {
                                    selectedClientId = client.id
                                    selectedProjectId = null
                                },
                            )
                            Text(
                                text = client.name,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                // Project selector (optional)
                if (filteredProjects.isNotEmpty()) {
                    Text("Projekt (volitelný)", style = MaterialTheme.typography.labelMedium)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedProjectId == null,
                                onClick = { selectedProjectId = null },
                            )
                            Text("(Žádný)", modifier = Modifier.padding(start = 4.dp))
                        }
                        filteredProjects.forEach { project ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = selectedProjectId == project.id,
                                    onClick = { selectedProjectId = project.id },
                                )
                                Text(
                                    text = project.name,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Title
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Název (volitelný)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Meeting type selector
                Text("Typ meetingu", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MeetingTypeEnum.entries.forEach { type ->
                        FilterChip(
                            selected = selectedMeetingType == type,
                            onClick = { selectedMeetingType = type },
                            label = { Text(getMeetingTypeLabel(type)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val clientId = selectedClientId ?: return@TextButton
                    onClassify(
                        clientId,
                        selectedProjectId,
                        title.takeIf { it.isNotBlank() },
                        selectedMeetingType,
                    )
                },
                enabled = selectedClientId != null,
            ) {
                Text("Klasifikovat")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        },
    )
}

private fun getMeetingTypeLabel(type: MeetingTypeEnum): String = when (type) {
    MeetingTypeEnum.MEETING -> "Meeting"
    MeetingTypeEnum.TASK_DISCUSSION -> "Diskuze"
    MeetingTypeEnum.STANDUP_PROJECT -> "Standup (projekt)"
    MeetingTypeEnum.STANDUP_TEAM -> "Standup (tým)"
    MeetingTypeEnum.INTERVIEW -> "Pohovor"
    MeetingTypeEnum.WORKSHOP -> "Workshop"
    MeetingTypeEnum.REVIEW -> "Review"
    MeetingTypeEnum.OTHER -> "Ostatní"
    MeetingTypeEnum.AD_HOC -> "Ad-hoc"
}

/**
 * List item for an offline meeting waiting to be synced.
 */
@Composable
private fun OfflineMeetingListItem(
    meeting: com.jervis.ui.storage.OfflineMeeting,
    onRetry: () -> Unit,
) {
    val stateText = when (meeting.syncState) {
        com.jervis.ui.storage.OfflineSyncState.PENDING -> "Čeká na odeslání"
        com.jervis.ui.storage.OfflineSyncState.SYNCING -> "Odesílání..."
        com.jervis.ui.storage.OfflineSyncState.SYNCED -> "Odesláno"
        com.jervis.ui.storage.OfflineSyncState.FAILED -> "Selhalo"
    }
    val stateColor = when (meeting.syncState) {
        com.jervis.ui.storage.OfflineSyncState.FAILED -> MaterialTheme.colorScheme.error
        com.jervis.ui.storage.OfflineSyncState.SYNCING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val duration = meeting.durationSeconds
    val durationText = if (duration > 0) {
        "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}"
    } else "—"

    androidx.compose.material3.OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title ?: "Offline nahrávka",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "$stateText · $durationText · ${meeting.chunkCount} chunků",
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor,
                )
                if (meeting.syncError != null) {
                    Text(
                        text = meeting.syncError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
            if (meeting.syncState == com.jervis.ui.storage.OfflineSyncState.FAILED) {
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text("Zkusit znovu")
                }
            }
            if (meeting.syncState == com.jervis.ui.storage.OfflineSyncState.SYNCING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}
