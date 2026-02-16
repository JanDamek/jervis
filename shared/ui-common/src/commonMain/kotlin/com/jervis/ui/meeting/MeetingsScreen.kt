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
import com.jervis.dto.meeting.AudioInputType
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

    var showSetupDialog by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
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
        if (showCorrections) {
            CorrectionsScreen(
                correctionService = viewModel.repository.transcriptCorrections,
                clientId = currentDetail.clientId,
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
}
