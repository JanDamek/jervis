package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.ui.design.JEditButton
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JervisSpacing

/**
 * Transcript panel with toggle chips, action buttons, and scrollable segment list.
 * Each row shows: [time] [selectable text] [edit button] [play/stop button]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptPanel(
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
                        label = { Text("Opravený") },
                    )
                    FilterChip(
                        selected = !showCorrected,
                        onClick = { onShowCorrectedChange(false) },
                        label = { Text("Surový") },
                    )
                }
                if (meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    JTextButton(onClick = onRetranscribe) { Text("Přepsat znovu") }
                }
                if (meeting.state in listOf(MeetingStateEnum.CORRECTED, MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED)) {
                    JTextButton(onClick = onRecorrect) { Text("Opravit znovu") }
                }
                if (meeting.state == MeetingStateEnum.INDEXED) {
                    JTextButton(onClick = onReindex) { Text("Přeindexovat") }
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
                        text = "Přepis je prázdný",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "Přepis zatím není k dispozici",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        JEditButton(onClick = onEdit)

        // Play/Stop button
        JIconButton(
            onClick = onPlayToggle,
            icon = if (isPlayingSegment) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isPlayingSegment) "Zastavit" else "Přehrát",
            tint = if (isPlayingSegment) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
