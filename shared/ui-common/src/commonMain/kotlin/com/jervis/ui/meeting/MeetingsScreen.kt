package com.jervis.ui.meeting

import androidx.compose.foundation.background
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
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.delay

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
    val isCorrecting by viewModel.isCorrecting.collectAsState()
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

        // Auto-refresh while meeting is in a processing state
        val isProcessing = currentDetail.state in listOf(
            MeetingStateEnum.UPLOADED, MeetingStateEnum.TRANSCRIBING,
            MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING,
            MeetingStateEnum.CORRECTION_REVIEW,
        )
        LaunchedEffect(currentDetail.id, isProcessing) {
            if (isProcessing) {
                while (true) {
                    delay(5000)
                    viewModel.refreshMeeting(currentDetail.id)
                }
            }
        }

        MeetingDetailView(
            meeting = currentDetail,
            isPlaying = playingMeetingId == currentDetail.id,
            isCorrecting = isCorrecting,
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
                    text = stateLabel(meeting.state),
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
    isCorrecting: Boolean,
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
) {
    // Toggle between corrected and raw transcript
    var showCorrected by remember { mutableStateOf(true) }
    // Correction mode — clickable segments to submit corrections
    var correctionMode by remember { mutableStateOf(false) }
    // Segment click correction: stores (segmentIndex, segmentText)
    var segmentForCorrection by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Chat instruction input
    var instructionText by remember { mutableStateOf("") }
    // Overflow menu for compact screens
    var showOverflowMenu by remember { mutableStateOf(false) }

    BoxWithConstraints {
        val isCompact = maxWidth < 600.dp

    JDetailScreen(
        title = meeting.title ?: "Meeting",
        onBack = onBack,
        actions = {
            if (meeting.state != MeetingStateEnum.RECORDING) {
                IconButton(onClick = onPlayToggle) {
                    Text(
                        text = if (isPlaying) "\u23F9" else "\u25B6",
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
                            text = {
                                Text(
                                    if (correctionMode) "\u270F Rezim oprav (zap.)"
                                    else "\u270F Rezim oprav",
                                )
                            },
                            onClick = { correctionMode = !correctionMode; showOverflowMenu = false },
                        )
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
                IconButton(onClick = { correctionMode = !correctionMode }) {
                    Text(
                        text = "\u270F",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (correctionMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onCorrections) {
                    Text("\uD83D\uDCD6", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onRefresh) {
                    Text("\u21BB", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onDelete) {
                    Text("\uD83D\uDDD1", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    ) {
        // Metadata header
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

        Spacer(modifier = Modifier.height(12.dp))

        // Pipeline progress indicator
        PipelineProgress(state = meeting.state)

        Spacer(modifier = Modifier.height(16.dp))

        // Correction questions card (when agent needs user input)
        if (meeting.state == MeetingStateEnum.CORRECTION_REVIEW && meeting.correctionQuestions.isNotEmpty()) {
            CorrectionQuestionsCard(
                questions = meeting.correctionQuestions,
                onSubmitAnswers = onAnswerQuestions,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error message
        if (meeting.state == MeetingStateEnum.FAILED) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = meeting.errorMessage ?: "Neznama chyba",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Transcript content (when available)
        val hasRawTranscript = meeting.transcriptSegments.isNotEmpty() ||
            !meeting.transcriptText.isNullOrBlank()
        val hasCorrected = meeting.correctedTranscriptSegments.isNotEmpty() ||
            !meeting.correctedTranscriptText.isNullOrBlank()

        if (hasRawTranscript || hasCorrected) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Corrected/Raw toggle + action buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hasCorrected) {
                    FilterChip(
                        selected = showCorrected,
                        onClick = { showCorrected = true },
                        label = { Text("Opraveny") },
                    )
                    FilterChip(
                        selected = !showCorrected,
                        onClick = { showCorrected = false },
                        label = { Text("Surovy") },
                    )
                }
                if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    TextButton(onClick = onRetranscribe) {
                        Text("Prepsat znovu")
                    }
                }
                if (meeting.state in listOf(MeetingStateEnum.CORRECTED, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    TextButton(onClick = onRecorrect) {
                        Text("Opravit znovu")
                    }
                }
                if (meeting.state == MeetingStateEnum.INDEXED) {
                    TextButton(onClick = onReindex) {
                        Text("Preindexovat")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (showCorrected && hasCorrected) {
                TranscriptContent(
                    segments = meeting.correctedTranscriptSegments,
                    text = meeting.correctedTranscriptText,
                    correctionMode = correctionMode,
                    onSegmentClick = { index, seg -> segmentForCorrection = index to seg.text },
                )
            } else {
                TranscriptContent(
                    segments = meeting.transcriptSegments,
                    text = meeting.transcriptText,
                    correctionMode = correctionMode,
                    onSegmentClick = { index, seg -> segmentForCorrection = index to seg.text },
                )
            }

            // Chat correction input
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (isCorrecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Agent opravuje prepis...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = instructionText,
                    onValueChange = { instructionText = it },
                    label = { Text("Instrukce pro opravu") },
                    placeholder = { Text("Napr: 'Mazlusek' oprav na 'Mazlusek s.r.o.'") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isCorrecting,
                )
                TextButton(
                    onClick = {
                        if (instructionText.isNotBlank()) {
                            onCorrectWithInstruction(instructionText)
                            instructionText = ""
                        }
                    },
                    enabled = instructionText.isNotBlank() && !isCorrecting,
                ) {
                    Text("Opravit")
                }
            }
        }
    } // JDetailScreen
    } // BoxWithConstraints

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
private fun PipelineProgress(state: MeetingStateEnum) {
    if (state == MeetingStateEnum.RECORDING) return

    val (currentStepIndex, isActive) = stateToStepInfo(state)

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

            // Status description
            val statusText = when {
                state == MeetingStateEnum.FAILED -> null // handled separately
                state == MeetingStateEnum.INDEXED -> "Zpracovani dokonceno"
                // Waiting in queue (step done, next not started yet)
                state == MeetingStateEnum.UPLOADED -> "Ve fronte - ceka na prepis pres Whisper"
                state == MeetingStateEnum.TRANSCRIBED -> "Ve fronte - ceka na korekci pres LLM model"
                state == MeetingStateEnum.CORRECTION_REVIEW -> "Agent potrebuje vase odpovedi"
                state == MeetingStateEnum.CORRECTED -> "Ve fronte - ceka na indexaci do znalostni baze"
                // Actively processing
                isActive && currentStepIndex in pipelineSteps.indices ->
                    pipelineSteps[currentStepIndex].activeDescription
                else -> null
            }

            if (statusText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isActive || state in listOf(MeetingStateEnum.UPLOADED, MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTED)) {
                        LinearProgressIndicator(
                            modifier = Modifier.width(80.dp).height(3.dp),
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == MeetingStateEnum.INDEXED)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptContent(
    segments: List<TranscriptSegmentDto>,
    text: String?,
    correctionMode: Boolean = false,
    onSegmentClick: (Int, TranscriptSegmentDto) -> Unit = { _, _ -> },
) {
    if (segments.isNotEmpty()) {
        segments.forEachIndexed { index, segment ->
            TranscriptSegmentRow(
                segment = segment,
                correctionMode = correctionMode,
                onClick = { onSegmentClick(index, segment) },
            )
        }
    } else if (!text.isNullOrBlank()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            text = "Prepis je prazdny",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegmentDto,
    correctionMode: Boolean = false,
    onClick: () -> Unit = {},
) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .let {
            if (correctionMode) {
                it
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            } else {
                it
            }
        }

    Row(modifier = modifier) {
        Text(
            text = formatDuration(segment.startSec.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
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
 * Each question has options (radio) or a free text input.
 * User answers are submitted as correction rules.
 */
@Composable
private fun CorrectionQuestionsCard(
    questions: List<CorrectionQuestionDto>,
    onSubmitAnswers: (List<CorrectionAnswerDto>) -> Unit,
) {
    // Track answers: questionId -> corrected text
    val answers = remember { mutableStateOf(questions.associate { it.questionId to "" }) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Agent potrebuje vase upesneni",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Opravte nebo potvdte spravny tvar nasledujicich vyrazu:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            questions.forEach { question ->
                CorrectionQuestionItem(
                    question = question,
                    currentAnswer = answers.value[question.questionId] ?: "",
                    onAnswerChanged = { newAnswer ->
                        answers.value = answers.value.toMutableMap().apply {
                            put(question.questionId, newAnswer)
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val allAnswered = questions.all { q ->
                    (answers.value[q.questionId] ?: "").isNotBlank()
                }
                TextButton(
                    onClick = {
                        val answerDtos = questions.mapNotNull { q ->
                            val corrected = answers.value[q.questionId]?.trim()
                            if (!corrected.isNullOrBlank()) {
                                CorrectionAnswerDto(
                                    questionId = q.questionId,
                                    segmentIndex = q.segmentIndex,
                                    original = q.originalText,
                                    corrected = corrected,
                                )
                            } else {
                                null
                            }
                        }
                        onSubmitAnswers(answerDtos)
                    },
                    enabled = allAnswered,
                ) {
                    Text("Odeslat odpovedi")
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
    onAnswerChanged: (String) -> Unit,
) {
    Column {
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Puvodne: \"${question.originalText}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (question.correctionOptions.isNotEmpty()) {
            // Radio options
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

        // Free text input (always shown, pre-filled if option selected)
        OutlinedTextField(
            value = currentAnswer,
            onValueChange = onAnswerChanged,
            label = { Text("Spravny tvar") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
        )
    }
}
