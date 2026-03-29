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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
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
import com.jervis.dto.client.ClientDto
import com.jervis.dto.project.ProjectDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingGroupDto
import com.jervis.dto.meeting.MeetingSummaryDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.design.JAddButton
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.storage.RecordingSession
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
    uploadService: RecordingUploadService? = null,
) {
    val vmProjects by viewModel.projects.collectAsState()
    val vmProjectGroups by viewModel.projectGroups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val selectedMeeting by viewModel.selectedMeeting.collectAsState()
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
    val pendingSessions = uploadService?.sessions?.collectAsState()?.value ?: emptyList()

    val currentWeekMeetings by viewModel.currentWeekMeetings.collectAsState()
    val olderGroups by viewModel.olderGroups.collectAsState()
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    val loadingGroups by viewModel.loadingGroups.collectAsState()
    val clientSpeakers by viewModel.clientSpeakers.collectAsState()
    val playbackPositionSec by viewModel.playbackPositionSec.collectAsState()
    val playbackDurationSec by viewModel.playbackDurationSec.collectAsState()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MeetingDto?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPermanentDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    val audioRecorder = remember { AudioRecorder() }

    // Load unclassified meetings once on startup
    LaunchedEffect(Unit) {
        viewModel.loadUnclassifiedMeetings()
    }

    // Load meetings + projects when global selection changes
    // Skip "__global__" — it's not a valid MongoDB ObjectId, only used for global chat
    LaunchedEffect(selectedClientId, selectedProjectId, showTrash) {
        selectedClientId?.takeIf { it != "__global__" }?.let { clientId ->
            if (showTrash) {
                viewModel.loadDeletedMeetings(clientId, selectedProjectId)
            } else {
                viewModel.loadTimeline(clientId, selectedProjectId)
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

    // Edit / classify meeting dialog — must be above detail/list return branches
    editTarget?.let { meeting ->
        EditMeetingDialog(
            meeting = meeting,
            clients = clients,
            projects = vmProjects,
            projectGroups = vmProjectGroups,
            onLoadProjects = { clientId ->
                viewModel.loadProjects(clientId)
                viewModel.loadProjectGroups(clientId)
            },
            onSave = { clientId, projectId, groupId, title, meetingType ->
                if (meeting.clientId == null) {
                    viewModel.classifyMeeting(
                        meetingId = meeting.id,
                        clientId = clientId,
                        projectId = projectId,
                        groupId = groupId,
                        title = title,
                        meetingType = meetingType,
                    )
                } else {
                    viewModel.updateMeeting(
                        meetingId = meeting.id,
                        clientId = clientId,
                        projectId = projectId,
                        groupId = groupId,
                        title = title,
                        meetingType = meetingType,
                    )
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

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
            onEdit = { editTarget = currentDetail },
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
            clientSpeakers = clientSpeakers,
            onAssignSpeakers = { mapping -> viewModel.assignSpeakers(currentDetail.id, mapping) },
            onCreateSpeaker = { request -> viewModel.createSpeaker(request) },
            onSetVoiceSample = { speakerId, voiceSample -> viewModel.setVoiceSample(speakerId, voiceSample) },
            onSetVoiceEmbedding = { request -> viewModel.setVoiceEmbedding(request) },
            playbackPositionSec = if (playingMeetingId == currentDetail.id) playbackPositionSec else 0.0,
            playbackDurationSec = if (playingMeetingId == currentDetail.id) playbackDurationSec else 0.0,
            onSeek = { viewModel.seekPlayback(it) },
            onUpdateSpeakerMapping = { label, speakerId ->
                val currentMapping = currentDetail.speakerMapping.toMutableMap()
                if (speakerId != null) {
                    currentMapping[label] = speakerId
                } else {
                    currentMapping.remove(label)
                }
                viewModel.assignSpeakers(currentDetail.id, currentMapping)
            },
        )

        // Delete confirmation dialog — rendered in detail view context (overlay on top of detail)
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
        return
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
                            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                // Active meetings view with timeline grouping
                when {
                    selectedClientId == null && unclassifiedMeetings.isEmpty() -> {
                        JEmptyState(message = "Vyberte klienta", icon = "")
                    }
                    isLoading && unclassifiedMeetings.isEmpty() -> {
                        JCenteredLoading()
                    }
                    currentWeekMeetings.isEmpty() && olderGroups.isEmpty() && !isRecording
                        && unclassifiedMeetings.isEmpty() && pendingSessions.isEmpty() -> {
                        JEmptyState(message = "Zatím žádné nahrávky", icon = "")
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                        onClassify = { editTarget = meeting },
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

                            // Pending upload sessions section
                            if (pendingSessions.isNotEmpty()) {
                                item(key = "upload_header") {
                                    Text(
                                        text = "Odesílání nahrávek",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                items(pendingSessions, key = { "upload_${it.localId}" }) { session ->
                                    PendingSessionListItem(
                                        session = session,
                                        onRetry = { uploadService?.retrySession(session.localId) },
                                        onCancel = { uploadService?.cancelSession(session.localId, session.serverMeetingId) },
                                        onStop = {
                                            uploadService?.updateSession(session.localId) {
                                                it.copy(stoppedAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds())
                                            }
                                        },
                                    )
                                }
                                item(key = "upload_divider") {
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                            }

                            // Current week — always expanded
                            if (currentWeekMeetings.isNotEmpty()) {
                                item(key = "week_header") {
                                    Text(
                                        text = "Tento týden",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                items(currentWeekMeetings, key = { "cw_${it.id}" }) { meeting ->
                                    MeetingSummaryListItem(
                                        meeting = meeting,
                                        onClick = { viewModel.selectMeeting(null); viewModel.selectMeetingById(meeting.id) },
                                    )
                                }
                            }

                            // Older groups — collapsed by default, expand on click
                            for (group in olderGroups) {
                                val key = group.periodStart
                                val isExpanded = expandedGroups.containsKey(key)
                                val isGroupLoading = loadingGroups.contains(key)

                                item(key = "group_$key") {
                                    TimelineGroupHeader(
                                        group = group,
                                        isExpanded = isExpanded,
                                        isLoading = isGroupLoading,
                                        onClick = { viewModel.toggleGroup(group) },
                                    )
                                }

                                if (isExpanded) {
                                    val groupItems = expandedGroups[key] ?: emptyList()
                                    items(groupItems, key = { "g_${key}_${it.id}" }) { meeting ->
                                        MeetingSummaryListItem(
                                            meeting = meeting,
                                            onClick = { viewModel.selectMeeting(null); viewModel.selectMeetingById(meeting.id) },
                                        )
                                    }
                                }
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
            onStart = { clientId, projectId, audioInputType, selectedDevice, title, meetingType, liveAssist ->
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
                    liveAssist = liveAssist,
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
 * Unified dialog for classifying an ad-hoc meeting or editing an already-classified one.
 * When meeting.clientId == null, acts as classification (client required, title says "Klasifikovat").
 * Otherwise acts as edit (fields pre-filled, reassignment warning shown).
 */
@Composable
private fun EditMeetingDialog(
    meeting: MeetingDto,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<com.jervis.dto.project.ProjectGroupDto> = emptyList(),
    onLoadProjects: (String) -> Unit,
    onSave: (clientId: String, projectId: String?, groupId: String?, title: String?, meetingType: MeetingTypeEnum?) -> Unit,
    onDismiss: () -> Unit,
) {
    val isClassification = meeting.clientId == null

    var selectedClientId by remember { mutableStateOf(meeting.clientId) }
    var selectedProjectId by remember { mutableStateOf(meeting.projectId) }
    var selectedGroupId by remember { mutableStateOf(meeting.groupId) }
    var title by remember { mutableStateOf(meeting.title ?: "") }
    var selectedMeetingType by remember { mutableStateOf(meeting.meetingType) }

    val clientChanged = selectedClientId != meeting.clientId
    val projectChanged = selectedProjectId != meeting.projectId
    val reassign = !isClassification && (clientChanged || projectChanged)

    // Load projects + groups when client changes
    LaunchedEffect(selectedClientId) {
        selectedClientId?.let { onLoadProjects(it) }
    }

    val filteredProjects = projects.filter { it.clientId == selectedClientId }
    val filteredGroups = projectGroups.filter { it.clientId == selectedClientId }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isClassification) "Klasifikovat nahrávku" else "Editovat meeting") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Title
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isClassification) "Název (volitelný)" else "Název") },
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
                                    selectedGroupId = null
                                },
                            )
                            Text(
                                text = client.name,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                // Project group selector
                if (filteredGroups.isNotEmpty()) {
                    Text("Skupina (volitelná)", style = MaterialTheme.typography.labelMedium)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedGroupId == null,
                                onClick = { selectedGroupId = null },
                            )
                            Text("(Žádná)", modifier = Modifier.padding(start = 4.dp))
                        }
                        filteredGroups.forEach { group ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = selectedGroupId == group.id,
                                    onClick = { selectedGroupId = group.id },
                                )
                                Text(
                                    text = group.name,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Project selector
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

                // Reassignment warning (only for edit, not classification)
                if (reassign && meeting.state == MeetingStateEnum.INDEXED) {
                    Text(
                        text = "Přeřazení smaže indexované informace a znovu je vytvoří pro nového klienta/projekt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val clientId = selectedClientId ?: return@TextButton
                    onSave(
                        clientId,
                        selectedProjectId,
                        selectedGroupId,
                        title.takeIf { it.isNotBlank() },
                        selectedMeetingType,
                    )
                },
                enabled = selectedClientId != null,
            ) {
                Text(if (isClassification) "Klasifikovat" else "Uložit")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        },
    )
}

/**
 * List item for a pending recording session (uploading chunks to server).
 */
@Composable
private fun PendingSessionListItem(
    session: RecordingSession,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit = {},
) {
    val statusText = when {
        session.error != null -> "Selhalo: ${session.error}"
        session.stoppedAtMs == null -> "Nahrává..."
        session.serverMeetingId == null -> "Čeká na připojení"
        session.uploadedChunkCount < session.chunkCount -> "Odesílání ${session.uploadedChunkCount}/${session.chunkCount}"
        else -> "Dokončování..."
    }
    val statusColor = when {
        session.error != null -> MaterialTheme.colorScheme.error
        session.stoppedAtMs == null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    androidx.compose.material3.OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title ?: "Bez názvu",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                    if (session.chunkCount > 0 && session.stoppedAtMs != null) {
                        val progress = if (session.chunkCount > 0)
                            session.uploadedChunkCount.toFloat() / session.chunkCount
                        else 0f
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (session.error != null) {
                        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Zkusit znovu") }
                    }
                    if (session.stoppedAtMs == null) {
                        androidx.compose.material3.TextButton(onClick = onStop) { Text("Zastavit") }
                    }
                    androidx.compose.material3.TextButton(onClick = onCancel) { Text("Zrušit") }
                }
            }
        }
    }
}

@Composable
private fun TimelineGroupHeader(
    group: MeetingGroupDto,
    isExpanded: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (isExpanded) "▼" else "▶"
            Text(
                text = icon,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = "${group.count} nahrávek",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
