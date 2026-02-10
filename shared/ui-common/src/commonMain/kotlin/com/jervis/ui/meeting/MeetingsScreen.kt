package com.jervis.ui.meeting

import androidx.compose.foundation.background
import kotlin.time.Clock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.design.COMPACT_BREAKPOINT_DP
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JVerticalSplitLayout
import com.jervis.ui.design.JervisSpacing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll


/**
 * Meetings screen - list of recordings with detail view.
 * Supports starting new recordings, viewing transcripts, and managing meetings.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val correctionProgress by viewModel.correctionProgress.collectAsState()
    val pendingChatMessage by viewModel.pendingChatMessage.collectAsState()
    val error by viewModel.error.collectAsState()

    // Filter state — pre-filled from main window selection
    var filterClientId by remember { mutableStateOf(selectedClientId ?: clients.firstOrNull()?.id) }
    var filterProjectId by remember { mutableStateOf<String?>(selectedProjectId) }

    val deletedMeetings by viewModel.deletedMeetings.collectAsState()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPermanentDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    val audioRecorder = remember { AudioRecorder() }

    // Load meetings + projects when filter changes
    LaunchedEffect(filterClientId, filterProjectId, showTrash) {
        filterClientId?.let { clientId ->
            if (showTrash) {
                viewModel.loadDeletedMeetings(clientId, filterProjectId)
            } else {
                viewModel.loadMeetings(clientId, filterProjectId)
            }
            viewModel.loadProjects(clientId)
        }
    }

    // Subscribe to real-time events
    LaunchedEffect(filterClientId) {
        filterClientId?.let { viewModel.subscribeToEvents(it) }
    }

    // Corrections sub-view state
    var showCorrections by remember { mutableStateOf(false) }

    // Detail view
    val currentDetail = selectedMeeting
    if (currentDetail != null) {
        if (showCorrections) {
            CorrectionsScreen(
                correctionService = viewModel.correctionService,
                clientId = currentDetail.clientId,
                projectId = currentDetail.projectId,
                onBack = { showCorrections = false },
            )
            return
        }

        // Real-time updates via event stream - no polling needed

        MeetingDetailView(
            meeting = currentDetail,
            isPlaying = playingMeetingId == currentDetail.id,
            playingSegmentIndex = if (playingMeetingId == currentDetail.id) playingSegmentIndex else -1,
            isCorrecting = isCorrecting,
            pendingChatMessage = pendingChatMessage,
            transcriptionPercent = transcriptionProgress[currentDetail.id],
            correctionProgress = correctionProgress[currentDetail.id],
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
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { showSetupDialog = true },
                        enabled = !isRecording,
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
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
            // Client/Project filter
            ClientProjectFilter(
                clients = clients,
                projects = vmProjects,
                selectedClientId = filterClientId,
                selectedProjectId = filterProjectId,
                onClientSelected = { id ->
                    filterClientId = id
                    filterProjectId = null
                },
                onProjectSelected = { id -> filterProjectId = id },
            )

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
                    label = { Text("Nahravky") },
                )
                FilterChip(
                    selected = showTrash,
                    onClick = { showTrash = true },
                    label = { Text("\uD83D\uDDD1 Kos") },
                )
            }

            // Recording indicator (when recording)
            if (isRecording && !showTrash) {
                RecordingIndicator(
                    durationSeconds = recordingDuration,
                    onStop = { viewModel.stopRecording() },
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
                    filterClientId == null -> {
                        JEmptyState(message = "Vyberte klienta", icon = "")
                    }
                    isLoading -> {
                        JCenteredLoading()
                    }
                    deletedMeetings.isEmpty() -> {
                        JEmptyState(message = "Kos je prazdny", icon = "\uD83D\uDDD1")
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
                    filterClientId == null -> {
                        JEmptyState(message = "Vyberte klienta", icon = "")
                    }
                    isLoading -> {
                        JCenteredLoading()
                    }
                    displayedMeetings.isEmpty() && !isRecording -> {
                        JEmptyState(message = "Zatim zadne nahravky", icon = "")
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

    // Setup dialog
    if (showSetupDialog) {
        RecordingSetupDialog(
            clients = clients,
            projects = vmProjects,
            selectedClientId = filterClientId,
            selectedProjectId = filterProjectId,
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

    // Delete confirmation dialog (soft delete → trash)
    showDeleteConfirmDialog?.let { meetingId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Smazat nahravku?") },
            text = {
                Text("Nahravka bude presunuta do kose, kde zustane 30 dni. Pote bude trvale smazana.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = null
                        viewModel.deleteMeeting(meetingId)
                        viewModel.selectMeeting(null)
                    },
                ) {
                    Text("Smazat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Zrusit")
                }
            },
        )
    }

    // Permanent delete confirmation dialog
    showPermanentDeleteConfirmDialog?.let { meetingId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPermanentDeleteConfirmDialog = null },
            title = { Text("Trvale smazat?") },
            text = {
                Text("Nahravka bude trvale smazana vcetne audio souboru. Tuto akci nelze vratit zpet.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermanentDeleteConfirmDialog = null
                        viewModel.permanentlyDeleteMeeting(meetingId)
                    },
                ) {
                    Text("Trvale smazat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteConfirmDialog = null }) {
                    Text("Zrusit")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientProjectFilter(
    clients: List<ClientDto>,
    projects: List<com.jervis.dto.ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Client dropdown
        var clientExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = clientExpanded,
            onExpandedChange = { clientExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = clients.find { it.id == selectedClientId }?.name ?: "Vyberte klienta...",
                onValueChange = {},
                readOnly = true,
                label = { Text("Klient") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = clientExpanded,
                onDismissRequest = { clientExpanded = false },
            ) {
                clients.forEach { client ->
                    DropdownMenuItem(
                        text = { Text(client.name) },
                        onClick = {
                            onClientSelected(client.id)
                            clientExpanded = false
                        },
                    )
                }
            }
        }

        // Project dropdown
        var projectExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = projects.find { it.id == selectedProjectId }?.name ?: "(Vse)",
                onValueChange = {},
                readOnly = true,
                label = { Text("Projekt") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                singleLine = true,
                enabled = selectedClientId != null,
            )
            ExposedDropdownMenu(
                expanded = projectExpanded,
                onDismissRequest = { projectExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("(Vse)") },
                    onClick = {
                        onProjectSelected(null)
                        projectExpanded = false
                    },
                )
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            onProjectSelected(project.id)
                            projectExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Format ISO 8601 startedAt to "YYYY-MM-DD HH:MM" */
private fun formatDateTime(isoString: String): String =
    isoString.take(16).replace('T', ' ')

@Composable
private fun MeetingListItem(
    meeting: MeetingDto,
    isPlaying: Boolean,
    transcriptionPercent: Double? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    onClick: () -> Unit,
    onPlayToggle: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play button (only for meetings with audio)
            if (meeting.state != MeetingStateEnum.RECORDING) {
                IconButton(
                    onClick = onPlayToggle,
                    modifier = Modifier.size(JervisSpacing.touchTarget),
                ) {
                    Text(
                        text = if (isPlaying) "\u23F9" else "\u25B6",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title ?: "Meeting ${meeting.id.takeLast(6)}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDateTime(meeting.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    meeting.meetingType?.let { type ->
                        Text(
                            text = meetingTypeLabel(type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duration
            meeting.durationSeconds?.let { dur ->
                Text(
                    text = formatDuration(dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // State indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stateIcon(meeting.state),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when {
                        meeting.state == MeetingStateEnum.TRANSCRIBING && transcriptionPercent != null ->
                            "Prepis ${transcriptionPercent.toInt()}%"
                        meeting.state == MeetingStateEnum.CORRECTING && correctionProgress != null ->
                            "Korekce ${correctionProgress.chunksDone}/${correctionProgress.totalChunks}"
                        else -> stateLabel(meeting.state)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meeting.state == MeetingStateEnum.FAILED)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeletedMeetingListItem(
    meeting: MeetingDto,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title ?: "Meeting ${meeting.id.takeLast(6)}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDateTime(meeting.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    meeting.deletedAt?.let { deletedAt ->
                        Text(
                            text = "Smazano: ${formatDateTime(deletedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Restore button
            IconButton(
                onClick = onRestore,
                modifier = Modifier.size(JervisSpacing.touchTarget),
            ) {
                Text("\u21A9", style = MaterialTheme.typography.bodyLarge)
            }

            // Permanent delete button
            IconButton(
                onClick = onPermanentDelete,
                modifier = Modifier.size(JervisSpacing.touchTarget),
            ) {
                Text(
                    "\u2716",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingDetailView(
    meeting: MeetingDto,
    isPlaying: Boolean,
    playingSegmentIndex: Int = -1,
    isCorrecting: Boolean,
    pendingChatMessage: CorrectionChatMessageDto? = null,
    transcriptionPercent: Double? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onPlayToggle: () -> Unit,
    onRetranscribe: () -> Unit,
    onRecorrect: () -> Unit,
    onReindex: () -> Unit,
    onCorrections: () -> Unit,
    onAnswerQuestions: (List<CorrectionAnswerDto>) -> Unit,
    onApplySegmentCorrection: (segmentIndex: Int, correctedText: String) -> Unit,
    onCorrectWithInstruction: (instruction: String) -> Unit,
    onSegmentPlay: (segmentIndex: Int, startSec: Double, endSec: Double) -> Unit = { _, _, _ -> },
) {
    // Toggle between corrected and raw transcript
    var showCorrected by remember { mutableStateOf(true) }
    // Segment click correction: stores (segmentIndex, segmentText)
    var segmentForCorrection by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Overflow menu for compact screens
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Splitter fraction for expanded mode
    var splitFraction by remember { mutableStateOf(0.7f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < COMPACT_BREAKPOINT_DP.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            JTopBar(
                title = meeting.title ?: "Meeting",
                onBack = onBack,
                actions = {
                    if (meeting.state != MeetingStateEnum.RECORDING) {
                        IconButton(onClick = onPlayToggle) {
                            Text(
                                text = if (isPlaying && playingSegmentIndex < 0) "\u23F9" else "\u25B6",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    if (isCompact) {
                        // Compact: overflow menu for secondary actions
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Text("\u22EF", style = MaterialTheme.typography.bodyLarge)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCD6 Pravidla oprav") },
                                    onClick = { onCorrections(); showOverflowMenu = false },
                                )
                                if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                                    DropdownMenuItem(
                                        text = { Text("\u21BB Prepsat znovu") },
                                        onClick = { onRetranscribe(); showOverflowMenu = false },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("\u21BB Obnovit") },
                                    onClick = { onRefresh(); showOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDDD1 Smazat") },
                                    onClick = { onDelete(); showOverflowMenu = false },
                                )
                            }
                        }
                    } else {
                        // Expanded: all action buttons visible
                        IconButton(onClick = onCorrections) {
                            Text("\uD83D\uDCD6", style = MaterialTheme.typography.bodyLarge)
                        }
                        if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                            IconButton(onClick = onRetranscribe) {
                                Text("\uD83D\uDD04", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        IconButton(onClick = onRefresh) {
                            Text("\u21BB", style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = onDelete) {
                            Text("\uD83D\uDDD1", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                },
            )

            // Metadata header — scrollable when content overflows (error messages, correction questions)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = JervisSpacing.outerPadding),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatDateTime(meeting.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    meeting.meetingType?.let {
                        Text(
                            text = meetingTypeLabel(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    meeting.durationSeconds?.let {
                        Text(
                            text = formatDuration(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Pipeline progress indicator
                PipelineProgress(
                    state = meeting.state,
                    transcriptionPercent = transcriptionPercent,
                    correctionProgress = correctionProgress,
                    stateChangedAt = meeting.stateChangedAt,
                )

                // Correction questions card (when agent needs user input)
                if (meeting.state == MeetingStateEnum.CORRECTION_REVIEW && meeting.correctionQuestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CorrectionQuestionsCard(
                        questions = meeting.correctionQuestions,
                        onSubmitAnswers = onAnswerQuestions,
                    )
                }

                // Error message with retranscribe action
                if (meeting.state == MeetingStateEnum.FAILED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = meeting.errorMessage ?: "Neznámá chyba",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetranscribe) {
                                Text("Přepsat znovu")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Split layout: transcript on top, chat on bottom
            if (isCompact) {
                // Compact mode: vertical column, fixed chat height
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // Transcript panel (takes remaining space)
                    TranscriptPanel(
                        meeting = meeting,
                        showCorrected = showCorrected,
                        onShowCorrectedChange = { showCorrected = it },
                        playingSegmentIndex = playingSegmentIndex,
                        onSegmentEdit = { index, seg -> segmentForCorrection = index to seg.text },
                        onSegmentPlay = onSegmentPlay,
                        onRetranscribe = onRetranscribe,
                        onRecorrect = onRecorrect,
                        onReindex = onReindex,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    // Chat panel (fixed height in compact)
                    AgentChatPanel(
                        chatHistory = meeting.correctionChatHistory,
                        pendingMessage = pendingChatMessage,
                        isCorrecting = isCorrecting,
                        onSendInstruction = onCorrectWithInstruction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
            } else {
                // Expanded mode: draggable splitter
                JVerticalSplitLayout(
                    splitFraction = splitFraction,
                    onSplitChange = { splitFraction = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    topContent = { mod ->
                        TranscriptPanel(
                            meeting = meeting,
                            showCorrected = showCorrected,
                            onShowCorrectedChange = { showCorrected = it },
                            playingSegmentIndex = playingSegmentIndex,
                            onSegmentEdit = { index, seg -> segmentForCorrection = index to seg.text },
                            onSegmentPlay = onSegmentPlay,
                            onRetranscribe = onRetranscribe,
                            onRecorrect = onRecorrect,
                            onReindex = onReindex,
                            modifier = mod,
                        )
                    },
                    bottomContent = { mod ->
                        AgentChatPanel(
                            chatHistory = meeting.correctionChatHistory,
                            pendingMessage = pendingChatMessage,
                            isCorrecting = isCorrecting,
                            onSendInstruction = onCorrectWithInstruction,
                            modifier = mod,
                        )
                    },
                )
            }
        }
    }

    // Correction dialog for segment click — pre-fills corrected with original text
    if (segmentForCorrection != null) {
        val (segIndex, segText) = segmentForCorrection!!
        SegmentCorrectionDialog(
            segmentText = segText,
            onConfirm = { correctedText ->
                onApplySegmentCorrection(segIndex, correctedText)
                segmentForCorrection = null
            },
            onDismiss = { segmentForCorrection = null },
        )
    }
}

/**
 * Transcript panel with toggle chips, action buttons, and scrollable segment list.
 * Each row shows: [time] [selectable text] [edit button] [play/stop button]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptPanel(
    meeting: MeetingDto,
    showCorrected: Boolean,
    onShowCorrectedChange: (Boolean) -> Unit,
    playingSegmentIndex: Int = -1,
    onSegmentEdit: (Int, TranscriptSegmentDto) -> Unit,
    onSegmentPlay: (segmentIndex: Int, startSec: Double, endSec: Double) -> Unit,
    onRetranscribe: () -> Unit,
    onRecorrect: () -> Unit,
    onReindex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRawTranscript = meeting.transcriptSegments.isNotEmpty() ||
        !meeting.transcriptText.isNullOrBlank()
    val hasCorrected = meeting.correctedTranscriptSegments.isNotEmpty() ||
        !meeting.correctedTranscriptText.isNullOrBlank()

    Column(
        modifier = modifier.padding(horizontal = JervisSpacing.outerPadding),
    ) {
        if (hasRawTranscript || hasCorrected) {
            // Corrected/Raw toggle + action buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hasCorrected) {
                    FilterChip(
                        selected = showCorrected,
                        onClick = { onShowCorrectedChange(true) },
                        label = { Text("Opraveny") },
                    )
                    FilterChip(
                        selected = !showCorrected,
                        onClick = { onShowCorrectedChange(false) },
                        label = { Text("Surovy") },
                    )
                }
                if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    TextButton(onClick = onRetranscribe) { Text("Prepsat znovu") }
                }
                if (meeting.state in listOf(MeetingStateEnum.CORRECTED, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    TextButton(onClick = onRecorrect) { Text("Opravit znovu") }
                }
                if (meeting.state == MeetingStateEnum.INDEXED) {
                    TextButton(onClick = onReindex) { Text("Preindexovat") }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Segments in LazyColumn for performance
            val segments = if (showCorrected && hasCorrected) {
                meeting.correctedTranscriptSegments
            } else {
                meeting.transcriptSegments
            }
            val text = if (showCorrected && hasCorrected) {
                meeting.correctedTranscriptText
            } else {
                meeting.transcriptText
            }

            if (segments.isNotEmpty()) {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(segments.size) { index ->
                            val seg = segments[index]
                            val nextStartSec = if (index + 1 < segments.size) {
                                segments[index + 1].startSec
                            } else {
                                seg.endSec
                            }
                            TranscriptSegmentRow(
                                segment = seg,
                                isPlayingSegment = playingSegmentIndex == index,
                                onEdit = { onSegmentEdit(index, seg) },
                                onPlayToggle = { onSegmentPlay(index, seg.startSec, nextStartSec) },
                            )
                        }
                    }
                }
            } else if (!text.isNullOrBlank()) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Prepis je prazdny",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "Prepis zatim neni k dispozici",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Agent chat panel with conversation history and instruction input.
 */
@Composable
private fun AgentChatPanel(
    chatHistory: List<CorrectionChatMessageDto>,
    pendingMessage: CorrectionChatMessageDto?,
    isCorrecting: Boolean,
    onSendInstruction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var instructionText by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    // Build display messages: persisted + pending optimistic
    val displayMessages = remember(chatHistory, pendingMessage) {
        val messages = chatHistory.toMutableList()
        // Add pending message only if it's not already persisted
        if (pendingMessage != null && messages.none { it.role == com.jervis.dto.meeting.CorrectionChatRole.USER && it.text == pendingMessage.text && it.timestamp == pendingMessage.timestamp }) {
            messages.add(pendingMessage)
        }
        messages
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(displayMessages.size) {
        if (displayMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(displayMessages.size - 1)
        }
    }

    Column(modifier = modifier) {
        HorizontalDivider()

        // Chat history
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = JervisSpacing.outerPadding),
            state = chatListState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (displayMessages.isEmpty()) {
                item {
                    Text(
                        text = "Zadejte instrukci pro opravu prepisu...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(displayMessages.size) { index ->
                    ChatMessageBubble(message = displayMessages[index])
                }
            }

            // Processing indicator
            if (isCorrecting) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Agent opravuje prepis...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = JervisSpacing.outerPadding)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = instructionText,
                onValueChange = { instructionText = it },
                placeholder = { Text("Instrukce pro opravu...") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 3,
                enabled = !isCorrecting,
            )
            TextButton(
                onClick = {
                    if (instructionText.isNotBlank()) {
                        onSendInstruction(instructionText)
                        instructionText = ""
                    }
                },
                enabled = instructionText.isNotBlank() && !isCorrecting,
            ) {
                Text("Odeslat")
            }
        }
    }
}

/**
 * Chat message bubble. User messages align right, agent messages align left.
 */
@Composable
private fun ChatMessageBubble(message: CorrectionChatMessageDto) {
    val isUser = message.role == com.jervis.dto.meeting.CorrectionChatRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else if (message.status == "error") {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else if (message.status == "error") {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                if (!isUser && message.rulesCreated > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pravidel vytvoreno: ${message.rulesCreated}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
        // Timestamp
        Text(
            text = formatChatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** Format ISO timestamp to short "HH:MM" for chat bubbles. */
private fun formatChatTimestamp(isoString: String): String {
    // ISO 8601: "2025-01-15T10:30:45.123Z" -> "10:30"
    val timeStart = isoString.indexOf('T')
    if (timeStart < 0) return ""
    return isoString.substring(timeStart + 1).take(5)
}

/** Pipeline step definition for visual progress. */
private data class PipelineStep(
    val label: String,
    val description: String,
    val activeDescription: String,
)

private val pipelineSteps = listOf(
    PipelineStep("Nahrano", "Audio nahrano na server", "Nahrava se audio..."),
    PipelineStep("Prepis", "Whisper prepsal audio na text", "Whisper prepisuje audio na text..."),
    PipelineStep("Korekce", "LLM model opravil prepis pomoci slovniku", "LLM model opravuje prepis pomoci slovniku..."),
    PipelineStep("Indexace", "Prepis ulozen do znalostni baze", "Uklada se prepis do znalostni baze..."),
)

/** Maps MeetingStateEnum to pipeline step index (0-based) and whether step is active vs done. */
private fun stateToStepInfo(state: MeetingStateEnum): Pair<Int, Boolean> =
    when (state) {
        MeetingStateEnum.RECORDING -> -1 to true
        MeetingStateEnum.UPLOADING -> 0 to true
        MeetingStateEnum.UPLOADED -> 0 to false      // step 0 done, waiting in queue
        MeetingStateEnum.TRANSCRIBING -> 1 to true
        MeetingStateEnum.TRANSCRIBED -> 1 to false    // step 1 done, waiting in queue
        MeetingStateEnum.CORRECTING -> 2 to true
        MeetingStateEnum.CORRECTION_REVIEW -> 2 to false  // step 2 paused, waiting for user answers
        MeetingStateEnum.CORRECTED -> 2 to false      // step 2 done, waiting in queue
        MeetingStateEnum.INDEXED -> 3 to false         // all done
        MeetingStateEnum.FAILED -> -1 to false
    }

@Composable
private fun PipelineProgress(
    state: MeetingStateEnum,
    transcriptionPercent: Double? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    stateChangedAt: String? = null,
) {
    if (state == MeetingStateEnum.RECORDING) return

    val (currentStepIndex, isActive) = stateToStepInfo(state)

    // Compute elapsed minutes since state changed (for stuck detection)
    val elapsedMinutes = remember(stateChangedAt) {
        if (stateChangedAt == null) return@remember null
        try {
            val changedEpochMs = kotlinx.datetime.Instant.parse(stateChangedAt).toEpochMilliseconds()
            val nowEpochMs = Clock.System.now().toEpochMilliseconds()
            ((nowEpochMs - changedEpochMs) / 60_000).toInt()
        } catch (_: Exception) { null }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Step indicators row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pipelineSteps.forEachIndexed { index, step ->
                    if (index > 0) {
                        // Connector line
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .background(
                                    if (index <= currentStepIndex)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }

                    val isDone = index < currentStepIndex || (index == currentStepIndex && !isActive && state != MeetingStateEnum.FAILED)
                    val isCurrent = index == currentStepIndex ||
                        (isActive && index == currentStepIndex) ||
                        (!isActive && index == currentStepIndex + 1 && state != MeetingStateEnum.INDEXED && state != MeetingStateEnum.FAILED)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Circle indicator
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isDone -> MaterialTheme.colorScheme.primary
                                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCurrent && !isDone) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = if (isDone) "\u2713" else "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDone)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = step.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isDone -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stuck warning
            val isLikelyStuck = elapsedMinutes != null && (
                (state == MeetingStateEnum.TRANSCRIBING && elapsedMinutes >= 45 && transcriptionPercent == null) ||
                (state == MeetingStateEnum.CORRECTING && elapsedMinutes >= 45 && correctionProgress == null)
            )

            // Status description
            val elapsedSuffix = if (elapsedMinutes != null && elapsedMinutes > 0 && isActive) " (${elapsedMinutes} min)" else ""
            val statusText = when {
                state == MeetingStateEnum.FAILED -> null // handled separately
                state == MeetingStateEnum.INDEXED -> "Zpracovani dokonceno"
                // Waiting in queue (step done, next not started yet)
                state == MeetingStateEnum.UPLOADED -> "Ve fronte - ceka na prepis pres Whisper"
                isLikelyStuck && state == MeetingStateEnum.TRANSCRIBING ->
                    "Mozna zaseknuto - zadny progress ${elapsedMinutes} min. Zkuste 'Prepsat znovu'."
                state == MeetingStateEnum.TRANSCRIBING && transcriptionPercent != null ->
                    "Whisper prepisuje: ${transcriptionPercent.toInt()}%$elapsedSuffix"
                isLikelyStuck && state == MeetingStateEnum.CORRECTING ->
                    "Mozna zaseknuto - zadny progress ${elapsedMinutes} min."
                state == MeetingStateEnum.CORRECTING && correctionProgress != null ->
                    (correctionProgress.message ?: "Korekce: chunk ${correctionProgress.chunksDone}/${correctionProgress.totalChunks}") + elapsedSuffix
                state == MeetingStateEnum.TRANSCRIBED -> "Ve fronte - ceka na korekci pres LLM model"
                state == MeetingStateEnum.CORRECTION_REVIEW -> "Agent potrebuje vase odpovedi"
                state == MeetingStateEnum.CORRECTED -> "Ve fronte - ceka na indexaci do znalostni baze"
                // Actively processing
                isActive && currentStepIndex in pipelineSteps.indices ->
                    pipelineSteps[currentStepIndex].activeDescription + elapsedSuffix
                else -> null
            }

            if (statusText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state == MeetingStateEnum.TRANSCRIBING && transcriptionPercent != null) {
                        LinearProgressIndicator(
                            progress = { (transcriptionPercent / 100.0).toFloat() },
                            modifier = Modifier.width(80.dp).height(3.dp),
                        )
                    } else if (state == MeetingStateEnum.CORRECTING && correctionProgress != null) {
                        LinearProgressIndicator(
                            progress = { (correctionProgress.percent / 100.0).toFloat() },
                            modifier = Modifier.width(80.dp).height(3.dp),
                        )
                    } else if (isActive || state in listOf(MeetingStateEnum.UPLOADED, MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTED)) {
                        LinearProgressIndicator(
                            modifier = Modifier.width(80.dp).height(3.dp),
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isLikelyStuck -> MaterialTheme.colorScheme.error
                            state == MeetingStateEnum.INDEXED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Transcript segment row layout: [time] [text] [edit] [play/stop]
 * Text is selectable (wrapped in SelectionContainer at parent level).
 */
@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegmentDto,
    isPlayingSegment: Boolean = false,
    onEdit: () -> Unit = {},
    onPlayToggle: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Time column (fixed width)
        Text(
            text = formatDuration(segment.startSec.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Text column (fills remaining space)
        Row(modifier = Modifier.weight(1f)) {
            if (segment.speaker != null) {
                Text(
                    text = "${segment.speaker}: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Edit button
        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(JervisSpacing.touchTarget),
        ) {
            Text(
                text = "\u270F",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Play/Stop button
        IconButton(
            onClick = onPlayToggle,
            modifier = Modifier.size(JervisSpacing.touchTarget),
        ) {
            Text(
                text = if (isPlayingSegment) "\u23F9" else "\u25B6",
                style = MaterialTheme.typography.bodySmall,
                color = if (isPlayingSegment) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stateIcon(state: MeetingStateEnum): String =
    when (state) {
        MeetingStateEnum.RECORDING -> "\uD83D\uDD34"
        MeetingStateEnum.UPLOADING -> "\u2B06"
        MeetingStateEnum.UPLOADED -> "\u231B"
        MeetingStateEnum.TRANSCRIBING -> "\uD83C\uDFA4"
        MeetingStateEnum.TRANSCRIBED -> "\u23F3"
        MeetingStateEnum.CORRECTING -> "\u270D"
        MeetingStateEnum.CORRECTION_REVIEW -> "\u2753"
        MeetingStateEnum.CORRECTED -> "\u23F3"
        MeetingStateEnum.INDEXED -> "\u2705"
        MeetingStateEnum.FAILED -> "\u274C"
    }

private fun stateLabel(state: MeetingStateEnum): String =
    when (state) {
        MeetingStateEnum.RECORDING -> "Nahrava se"
        MeetingStateEnum.UPLOADING -> "Odesila se"
        MeetingStateEnum.UPLOADED -> "Ceka na prepis"
        MeetingStateEnum.TRANSCRIBING -> "Prepisuje se"
        MeetingStateEnum.TRANSCRIBED -> "Ceka na korekci"
        MeetingStateEnum.CORRECTING -> "Opravuje se"
        MeetingStateEnum.CORRECTION_REVIEW -> "Ceka na odpoved"
        MeetingStateEnum.CORRECTED -> "Ceka na indexaci"
        MeetingStateEnum.INDEXED -> "Hotovo"
        MeetingStateEnum.FAILED -> "Chyba"
    }

private fun meetingTypeLabel(type: MeetingTypeEnum): String =
    when (type) {
        MeetingTypeEnum.MEETING -> "Schuzka"
        MeetingTypeEnum.TASK_DISCUSSION -> "Diskuse ukolu"
        MeetingTypeEnum.STANDUP_PROJECT -> "Standup projekt"
        MeetingTypeEnum.STANDUP_TEAM -> "Standup tym"
        MeetingTypeEnum.INTERVIEW -> "Pohovor"
        MeetingTypeEnum.WORKSHOP -> "Workshop"
        MeetingTypeEnum.REVIEW -> "Review"
        MeetingTypeEnum.OTHER -> "Jine"
    }

/**
 * Card showing correction questions from the agent.
 * Each question can be confirmed individually (collapses to compact view).
 * Only the final "Odeslat vše" button sends all confirmed answers to the backend.
 * Card is height-limited and scrollable when there are many questions.
 */
@Composable
private fun CorrectionQuestionsCard(
    questions: List<CorrectionQuestionDto>,
    onSubmitAnswers: (List<CorrectionAnswerDto>) -> Unit,
) {
    // Track answers: questionId -> corrected text
    val answers = remember { mutableStateOf(questions.associate { it.questionId to "" }) }
    // Track which questions are confirmed (collapsed)
    val confirmed = remember { mutableStateOf(emptySet<String>()) }
    // Immediate UI feedback on submit
    var isSubmitting by remember { mutableStateOf(false) }

    val confirmedCount = confirmed.value.size
    val totalCount = questions.size

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            // Immediate feedback — show loading state
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = "Odesílám odpovědi...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                // Fixed header
                Text(
                    text = "Agent potřebuje vaše upřesnění",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Opravte nebo potvrďte správný tvar ($confirmedCount/$totalCount potvrzeno):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Questions area (outer metadata column handles scrolling)
                Column {
                    questions.forEachIndexed { index, question ->
                        val isConfirmed = question.questionId in confirmed.value
                        CorrectionQuestionItem(
                            question = question,
                            currentAnswer = answers.value[question.questionId] ?: "",
                            isConfirmed = isConfirmed,
                            onAnswerChanged = { newAnswer ->
                                answers.value = answers.value.toMutableMap().apply {
                                    put(question.questionId, newAnswer)
                                }
                            },
                            onConfirm = {
                                confirmed.value = confirmed.value + question.questionId
                            },
                            onEdit = {
                                confirmed.value = confirmed.value - question.questionId
                            },
                        )
                        if (index < questions.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            )
                        }
                    }
                }

                // Submit all confirmed answers
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val answerDtos = questions.mapNotNull { q ->
                                if (q.questionId !in confirmed.value) return@mapNotNull null
                                val rawAnswer = answers.value[q.questionId]?.trim()
                                val isNevim = rawAnswer == "\u0000"
                                if (isNevim) {
                                    // "Nevim" → send empty corrected string
                                    CorrectionAnswerDto(
                                        questionId = q.questionId,
                                        segmentIndex = q.segmentIndex,
                                        original = q.originalText,
                                        corrected = "",
                                    )
                                } else if (!rawAnswer.isNullOrBlank()) {
                                    CorrectionAnswerDto(
                                        questionId = q.questionId,
                                        segmentIndex = q.segmentIndex,
                                        original = q.originalText,
                                        corrected = rawAnswer,
                                    )
                                } else {
                                    null
                                }
                            }
                            if (answerDtos.isNotEmpty()) {
                                isSubmitting = true
                                onSubmitAnswers(answerDtos)
                            }
                        },
                        enabled = confirmedCount > 0,
                    ) {
                        Text("Odeslat vše ($confirmedCount)")
                    }
                }
            }
        }
    }
}

/**
 * Simple dialog for correcting a single transcript segment.
 * Pre-fills with the original text so the user just edits it.
 */
@Composable
private fun SegmentCorrectionDialog(
    segmentText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var correctedText by remember { mutableStateOf(segmentText) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opravit segment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Upravte text segmentu:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = correctedText,
                    onValueChange = { correctedText = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (correctedText.isNotBlank() && correctedText != segmentText) {
                        onConfirm(correctedText.trim())
                    }
                },
                enabled = correctedText.isNotBlank() && correctedText != segmentText,
            ) {
                Text("Ulozit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrusit")
            }
        },
    )
}

@Composable
private fun CorrectionQuestionItem(
    question: CorrectionQuestionDto,
    currentAnswer: String,
    isConfirmed: Boolean,
    onAnswerChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
) {
    val isNevim = currentAnswer == "\u0000"
    if (isConfirmed) {
        // Collapsed confirmed view — single row with answer summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEdit() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isNevim) {
                        "\"${question.originalText}\" → přepíše se znovu"
                    } else {
                        "\"${question.originalText}\" → \"$currentAnswer\""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isNevim) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "\u2713",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        // Expanded edit view
        Column {
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Původně: \"${question.originalText}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (question.correctionOptions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    question.correctionOptions.forEach { option ->
                        FilterChip(
                            selected = currentAnswer == option,
                            onClick = { onAnswerChanged(option) },
                            label = { Text(option) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = if (isNevim) "" else currentAnswer,
                    onValueChange = onAnswerChanged,
                    label = { Text("Správný tvar") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        onAnswerChanged("\u0000")
                        onConfirm()
                    },
                ) {
                    Text("Nevím")
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = currentAnswer.isNotBlank() && !isNevim,
                ) {
                    Text("Potvrdit")
                }
            }
        }
    }
}
