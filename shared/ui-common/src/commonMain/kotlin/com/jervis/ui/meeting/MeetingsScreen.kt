package com.jervis.ui.meeting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.ui.audio.AudioDevice
import com.jervis.ui.audio.AudioRecorder
import com.jervis.ui.audio.AudioRecordingConfig
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing

/**
 * Meetings screen - list of recordings with detail view.
 * Supports starting new recordings, viewing transcripts, and managing meetings.
 */
@Composable
fun MeetingsScreen(
    viewModel: MeetingViewModel,
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    onBack: () -> Unit,
) {
    val meetings by viewModel.meetings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val selectedMeeting by viewModel.selectedMeeting.collectAsState()
    val currentMeetingId by viewModel.currentMeetingId.collectAsState()
    val error by viewModel.error.collectAsState()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showFinalizeDialog by remember { mutableStateOf(false) }
    val audioRecorder = remember { AudioRecorder() }

    // Load meetings when screen opens or client changes
    LaunchedEffect(selectedClientId, selectedProjectId) {
        selectedClientId?.let { viewModel.loadMeetings(it, selectedProjectId) }
    }

    // Show finalize dialog when recording stops
    LaunchedEffect(isRecording, currentMeetingId) {
        if (!isRecording && currentMeetingId != null) {
            showFinalizeDialog = true
        }
    }

    // Detail view
    val currentDetail = selectedMeeting
    if (currentDetail != null) {
        MeetingDetailView(
            meeting = currentDetail,
            onBack = { viewModel.selectMeeting(null) },
            onDelete = { viewModel.deleteMeeting(currentDetail.id) },
            onRefresh = { viewModel.refreshMeeting(currentDetail.id) },
        )
        return
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
                        enabled = selectedClientId != null && !isRecording,
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Recording indicator (when recording)
            if (isRecording) {
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
            when {
                isLoading -> JCenteredLoading()
                meetings.isEmpty() -> JEmptyState(message = "Zatim zadne nahravky", icon = "🎙")
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(meetings, key = { it.id }) { meeting ->
                            MeetingListItem(
                                meeting = meeting,
                                onClick = { viewModel.selectMeeting(meeting) },
                            )
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
            projects = projects,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            audioDevices = audioRecorder.getAvailableInputDevices(),
            systemAudioCapability = audioRecorder.getSystemAudioCapabilities(),
            onStart = { clientId, projectId, audioInputType, selectedDevice ->
                showSetupDialog = false
                viewModel.startRecording(
                    clientId = clientId,
                    projectId = projectId,
                    audioInputType = audioInputType,
                    recordingConfig = AudioRecordingConfig(
                        inputDevice = selectedDevice,
                        captureSystemAudio = audioInputType == AudioInputType.MIXED,
                    ),
                )
            },
            onDismiss = { showSetupDialog = false },
        )
    }

    // Finalize dialog
    if (showFinalizeDialog) {
        RecordingFinalizeDialog(
            durationSeconds = recordingDuration,
            onFinalize = { title, meetingType ->
                showFinalizeDialog = false
                viewModel.finalizeRecording(title, meetingType)
            },
            onCancel = {
                showFinalizeDialog = false
                viewModel.cancelRecording()
            },
        )
    }
}

@Composable
private fun MeetingListItem(
    meeting: MeetingDto,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                        text = meeting.startedAt.take(10),
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
            Text(
                text = stateIcon(meeting.state),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun MeetingDetailView(
    meeting: MeetingDto,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    JDetailScreen(
        title = meeting.title ?: "Meeting",
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh) {
                Text("↻", style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onDelete) {
                Text("🗑", style = MaterialTheme.typography.bodyLarge)
            }
        },
    ) {
        // Metadata header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = meeting.startedAt.take(10),
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
            Text(
                text = stateIcon(meeting.state),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transcript or state message
        when (meeting.state) {
            MeetingStateEnum.RECORDING -> {
                Text("Probíhá nahrávání...", style = MaterialTheme.typography.bodyMedium)
            }
            MeetingStateEnum.UPLOADING, MeetingStateEnum.UPLOADED -> {
                Text("Čeká na přepis...", style = MaterialTheme.typography.bodyMedium)
            }
            MeetingStateEnum.TRANSCRIBING -> {
                JCenteredLoading()
                Text("Probíhá přepis...", style = MaterialTheme.typography.bodyMedium)
            }
            MeetingStateEnum.FAILED -> {
                Text(
                    text = meeting.errorMessage ?: "Neznámá chyba",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.INDEXED -> {
                if (meeting.transcriptSegments.isNotEmpty()) {
                    meeting.transcriptSegments.forEach { segment ->
                        TranscriptSegmentRow(segment)
                    }
                } else if (!meeting.transcriptText.isNullOrBlank()) {
                    Text(
                        text = meeting.transcriptText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "Přepis je prázdný",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptSegmentRow(segment: TranscriptSegmentDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
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

private fun stateIcon(state: MeetingStateEnum): String = when (state) {
    MeetingStateEnum.RECORDING -> "🔴"
    MeetingStateEnum.UPLOADING -> "⬆"
    MeetingStateEnum.UPLOADED -> "⏳"
    MeetingStateEnum.TRANSCRIBING -> "⏳"
    MeetingStateEnum.TRANSCRIBED -> "✅"
    MeetingStateEnum.INDEXED -> "✅"
    MeetingStateEnum.FAILED -> "❌"
}

private fun meetingTypeLabel(type: MeetingTypeEnum): String = when (type) {
    MeetingTypeEnum.MEETING -> "Schuzka"
    MeetingTypeEnum.TASK_DISCUSSION -> "Diskuse ukolu"
    MeetingTypeEnum.STANDUP_PROJECT -> "Standup projekt"
    MeetingTypeEnum.STANDUP_TEAM -> "Standup tym"
    MeetingTypeEnum.INTERVIEW -> "Pohovor"
    MeetingTypeEnum.WORKSHOP -> "Workshop"
    MeetingTypeEnum.REVIEW -> "Review"
    MeetingTypeEnum.OTHER -> "Jine"
}
