package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JIconButton

@Composable
internal fun MeetingListItem(
    meeting: MeetingDto,
    isPlaying: Boolean,
    transcriptionPercent: Double? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    onClick: () -> Unit,
    onPlayToggle: () -> Unit,
) {
    JCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play button (only for meetings with audio)
            if (meeting.state != MeetingStateEnum.RECORDING) {
                JIconButton(
                    onClick = onPlayToggle,
                    icon = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Zastavit" else "Přehrát",
                )
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
                            "Přepis ${transcriptionPercent.toInt()}%"
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
internal fun DeletedMeetingListItem(
    meeting: MeetingDto,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    JCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                            text = "Smazáno: ${formatDateTime(deletedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Restore button
            JIconButton(
                onClick = onRestore,
                icon = Icons.Default.Undo,
                contentDescription = "Obnovit",
            )

            // Permanent delete button
            JIconButton(
                onClick = onPermanentDelete,
                icon = Icons.Default.DeleteForever,
                contentDescription = "Trvale smazat",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
