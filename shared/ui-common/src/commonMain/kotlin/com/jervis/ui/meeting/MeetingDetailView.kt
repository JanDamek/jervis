package com.jervis.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.ui.design.COMPACT_BREAKPOINT_DP
import com.jervis.ui.design.JDeleteButton
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JVerticalSplitLayout
import com.jervis.ui.design.JervisSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeetingDetailView(
    meeting: MeetingDto,
    isPlaying: Boolean,
    playingSegmentIndex: Int = -1,
    isCorrecting: Boolean,
    pendingChatMessage: CorrectionChatMessageDto? = null,
    transcriptionPercent: Double? = null,
    transcriptionLastSegment: String? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    onBack: () -> Unit,
    onEdit: () -> Unit,
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
    onDismissError: () -> Unit = {},
    onRetranscribeSegment: (segmentIndex: Int) -> Unit = {},
    onStopTranscription: () -> Unit = {},
    errorMessage: String? = null,
    onDismissViewError: () -> Unit = {},
    clientSpeakers: List<SpeakerDto> = emptyList(),
    onAssignSpeakers: (mapping: Map<String, String>) -> Unit = {},
    onCreateSpeaker: (SpeakerCreateDto) -> Unit = {},
    onSetVoiceSample: (speakerId: String, voiceSample: VoiceSampleRefDto) -> Unit = { _, _ -> },
    onSetVoiceEmbedding: (SpeakerEmbeddingDto) -> Unit = {},
    onUpdateSpeakerMapping: (label: String, speakerId: String?) -> Unit = { _, _ -> },
    playbackPositionSec: Double = 0.0,
    playbackDurationSec: Double = 0.0,
    onSeek: (Double) -> Unit = {},
) {
    // Toggle between corrected and raw transcript
    var showCorrected by remember { mutableStateOf(true) }
    // Segment click correction: stores full edit state with original + corrected text + timing
    var segmentForCorrection by remember { mutableStateOf<SegmentEditState?>(null) }
    // Overflow menu for compact screens
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Speaker assignment dialog visibility
    var showSpeakerDialog by remember { mutableStateOf(false) }
    // Splitter fraction for expanded mode (transcript/chat split)
    var splitFraction by remember { mutableStateOf(0.7f) }
    // Splitter fraction for correction panel (metadata+corrections / transcript+chat)
    val hasCorrectionQuestions = meeting.state == MeetingStateEnum.CORRECTION_REVIEW && meeting.correctionQuestions.isNotEmpty()
    var correctionSplitFraction by remember { mutableStateOf(0.4f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < COMPACT_BREAKPOINT_DP.dp
        val totalHeightPx = constraints.maxHeight.toFloat()

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            JTopBar(
                title = meeting.title ?: "Meeting",
                onBack = onBack,
                actions = {
                    if (meeting.state != MeetingStateEnum.RECORDING) {
                        JIconButton(
                            onClick = onPlayToggle,
                            icon = if (isPlaying && playingSegmentIndex < 0) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying && playingSegmentIndex < 0) "Zastavit" else "Přehrát",
                        )
                    }
                    if (isCompact) {
                        // Compact: overflow menu for secondary actions
                        Box {
                            JIconButton(
                                onClick = { showOverflowMenu = true },
                                icon = Icons.Default.MoreVert,
                                contentDescription = "Více",
                            )
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("Editovat")
                                        }
                                    },
                                    onClick = { onEdit(); showOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("Pravidla oprav")
                                        }
                                    },
                                    onClick = { onCorrections(); showOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("Mluvci")
                                        }
                                    },
                                    onClick = { showSpeakerDialog = true; showOverflowMenu = false },
                                )
                                if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                                                Text("Přepsat znovu")
                                            }
                                        },
                                        onClick = { onRetranscribe(); showOverflowMenu = false },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text("Obnovit")
                                        }
                                    },
                                    onClick = { onRefresh(); showOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                            Text("Smazat", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    onClick = { onDelete(); showOverflowMenu = false },
                                )
                            }
                        }
                    } else {
                        // Expanded: all action buttons visible
                        JIconButton(onClick = onEdit, icon = Icons.Default.Edit, contentDescription = "Editovat")
                        JIconButton(onClick = onCorrections, icon = Icons.Default.MenuBook, contentDescription = "Pravidla oprav")
                        JIconButton(
                            onClick = { showSpeakerDialog = true },
                            icon = Icons.Default.People,
                            contentDescription = "Mluvci",
                        )
                        if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                            JIconButton(onClick = onRetranscribe, icon = Icons.Default.Replay, contentDescription = "Přepsat znovu")
                        }
                        JRefreshButton(onClick = onRefresh)
                        JDeleteButton(onClick = onDelete)
                    }
                },
            )

            // ViewModel error (RPC failures etc.)
            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    JIconButton(
                        onClick = onDismissViewError,
                        icon = Icons.Default.Close,
                        contentDescription = "Zavřít",
                    )
                }
            }

            // Mini player with seekbar — visible during playback
            if (isPlaying && playingSegmentIndex < 0 && playbackDurationSec > 0) {
                MiniPlayerBar(
                    positionSec = playbackPositionSec,
                    durationSec = playbackDurationSec,
                    onSeek = onSeek,
                    onStop = onPlayToggle,
                )
            }

            // Metadata header — scrollable when content overflows (error messages, correction questions)
            // When correction questions are present, this section becomes resizable via splitter
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasCorrectionQuestions) Modifier.weight(correctionSplitFraction)
                        else Modifier.heightIn(max = 450.dp)
                    )
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
                    if (meeting.joinedByAgent) {
                        Text(
                            text = "Jervis se připojil",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (meeting.state == MeetingStateEnum.RECORDING && meeting.chunksReceived > 0) {
                        Text(
                            text = "Nahráváno: ${meeting.chunksReceived} úseků" +
                                (meeting.lastChunkAt?.let { " · poslední " + formatTime(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Pipeline progress indicator
                PipelineProgress(
                    state = meeting.state,
                    corrected = !meeting.correctedTranscriptText.isNullOrBlank(),
                    transcriptionPercent = transcriptionPercent,
                    correctionProgress = correctionProgress,
                    stateChangedAt = meeting.stateChangedAt,
                    lastSegmentText = transcriptionLastSegment ?: meeting.transcriptSegments.lastOrNull()?.text,
                    onStopTranscription = onStopTranscription,
                )

                // Correction questions card (when agent needs user input)
                if (meeting.state == MeetingStateEnum.CORRECTION_REVIEW && meeting.correctionQuestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CorrectionQuestionsCard(
                        questions = meeting.correctionQuestions,
                        onSubmitAnswers = onAnswerQuestions,
                        segments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments },
                        playingSegmentIndex = playingSegmentIndex,
                        onSegmentPlay = onSegmentPlay,
                    )
                }

                // Error message with retranscribe + dismiss actions
                if (meeting.state == MeetingStateEnum.FAILED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SelectionContainer {
                                Text(
                                    text = meeting.errorMessage ?: "Neznámá chyba",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                JTextButton(onClick = onRetranscribe) {
                                    Text("Přepsat znovu")
                                }
                                if (meeting.transcriptSegments.isNotEmpty()) {
                                    JTextButton(onClick = onDismissError) {
                                        Text("Zamítnout")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Draggable divider when correction questions panel is shown
            if (hasCorrectionQuestions) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(totalHeightPx) {
                            detectDragGestures { _, dragAmount ->
                                val delta = dragAmount.y / totalHeightPx
                                correctionSplitFraction = (correctionSplitFraction + delta).coerceIn(0.15f, 0.7f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                }
            }

            // Split layout: transcript on top, chat on bottom
            if (isCompact) {
                // Compact mode: vertical column, fixed chat height
                Column(
                    modifier = Modifier
                        .weight(if (hasCorrectionQuestions) 1f - correctionSplitFraction else 1f)
                        .fillMaxWidth(),
                ) {
                    // Transcript panel (takes remaining space)
                    TranscriptPanel(
                        meeting = meeting,
                        showCorrected = showCorrected,
                        onShowCorrectedChange = { showCorrected = it },
                        playingSegmentIndex = playingSegmentIndex,
                        onSegmentEdit = { index, seg ->
                            val rawSeg = meeting.transcriptSegments.getOrNull(index)
                            val correctedSeg = meeting.correctedTranscriptSegments.getOrNull(index)
                            val segments = if (showCorrected && meeting.correctedTranscriptSegments.isNotEmpty())
                                meeting.correctedTranscriptSegments else meeting.transcriptSegments
                            val nextStart = segments.getOrNull(index + 1)?.startSec ?: seg.endSec
                            segmentForCorrection = SegmentEditState(
                                segmentIndex = index,
                                originalText = rawSeg?.text ?: seg.text,
                                editableText = correctedSeg?.text ?: rawSeg?.text ?: seg.text,
                                startSec = seg.startSec,
                                endSec = nextStart,
                                speakerLabel = seg.speaker,
                                speakerName = seg.speakerName,
                                speakerId = seg.speakerId,
                                speakerConfidence = seg.speaker?.let { meeting.autoSpeakerMapping?.get(it)?.confidence },
                                matchedEmbeddingLabel = seg.speaker?.let { meeting.autoSpeakerMapping?.get(it)?.matchedEmbeddingLabel },
                            )
                        },
                        onSegmentPlay = onSegmentPlay,
                        onRetranscribe = onRetranscribe,
                        onRecorrect = onRecorrect,
                        onReindex = onReindex,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    // Bottom panel (chat)
                    AgentChatPanel(
                        chatHistory = meeting.correctionChatHistory,
                        pendingMessage = pendingChatMessage,
                        isCorrecting = isCorrecting,
                        onSendInstruction = onCorrectWithInstruction,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
            } else {
                // Expanded mode: draggable splitter
                JVerticalSplitLayout(
                    splitFraction = splitFraction,
                    onSplitChange = { splitFraction = it },
                    modifier = Modifier.weight(if (hasCorrectionQuestions) 1f - correctionSplitFraction else 1f).fillMaxWidth(),
                    topContent = { mod ->
                        TranscriptPanel(
                            meeting = meeting,
                            showCorrected = showCorrected,
                            onShowCorrectedChange = { showCorrected = it },
                            playingSegmentIndex = playingSegmentIndex,
                            onSegmentEdit = { index, seg ->
                                val rawSeg = meeting.transcriptSegments.getOrNull(index)
                                val correctedSeg = meeting.correctedTranscriptSegments.getOrNull(index)
                                val segments = if (showCorrected && meeting.correctedTranscriptSegments.isNotEmpty())
                                    meeting.correctedTranscriptSegments else meeting.transcriptSegments
                                val nextStart = segments.getOrNull(index + 1)?.startSec ?: seg.endSec
                                segmentForCorrection = SegmentEditState(
                                    segmentIndex = index,
                                    originalText = rawSeg?.text ?: seg.text,
                                    editableText = correctedSeg?.text ?: rawSeg?.text ?: seg.text,
                                    startSec = seg.startSec,
                                    endSec = nextStart,
                                    speakerLabel = seg.speaker,
                                    speakerName = seg.speakerName,
                                    speakerId = seg.speakerId,
                                    speakerConfidence = seg.speaker?.let { meeting.autoSpeakerMapping?.get(it)?.confidence },
                                    matchedEmbeddingLabel = seg.speaker?.let { meeting.autoSpeakerMapping?.get(it)?.matchedEmbeddingLabel },
                                )
                            },
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

    // Speaker assignment dialog
    SpeakerAssignmentDialog(
        visible = showSpeakerDialog,
        meeting = meeting,
        speakers = clientSpeakers,
        onAssignSpeakers = onAssignSpeakers,
        onCreateSpeaker = onCreateSpeaker,
        onSetVoiceSample = onSetVoiceSample,
        onSetVoiceEmbedding = onSetVoiceEmbedding,
        onDismiss = { showSpeakerDialog = false },
    )

    // Correction dialog for segment click — shows original + editable corrected text + audio playback
    if (segmentForCorrection != null) {
        val state = segmentForCorrection!!
        SegmentCorrectionDialog(
            originalText = state.originalText,
            editableText = state.editableText,
            isPlayingSegment = playingSegmentIndex == state.segmentIndex,
            onPlayToggle = { onSegmentPlay(state.segmentIndex, state.startSec, state.endSec) },
            onConfirm = { correctedText ->
                onApplySegmentCorrection(state.segmentIndex, correctedText)
                segmentForCorrection = null
                showCorrected = true
            },
            onDismiss = { segmentForCorrection = null },
            onRetranscribeSegment = {
                onRetranscribeSegment(state.segmentIndex)
                segmentForCorrection = null
            },
            speakerLabel = state.speakerLabel,
            speakerName = state.speakerName,
            speakerConfidence = state.speakerConfidence,
            matchedEmbeddingLabel = state.matchedEmbeddingLabel,
            availableSpeakers = clientSpeakers,
            onSpeakerChange = { speakerId ->
                val label = state.speakerLabel
                if (label != null && speakerId != null) {
                    onUpdateSpeakerMapping(label, speakerId)
                }
                segmentForCorrection = null
            },
        )
    }
}

@Composable
private fun MiniPlayerBar(
    positionSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatDuration(positionSec),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(44.dp),
            )
            Slider(
                value = positionSec.toFloat(),
                onValueChange = { onSeek(it.toDouble()) },
                valueRange = 0f..durationSec.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(durationSec),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(44.dp),
            )
            JIconButton(
                onClick = onStop,
                icon = Icons.Default.Stop,
                contentDescription = "Zastavit",
            )
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val totalSec = seconds.toInt().coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
