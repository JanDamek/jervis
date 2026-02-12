package com.jervis.ui.meeting

import kotlin.time.Clock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.ui.design.JIconButton

/** Pipeline step definition for visual progress. */
internal data class PipelineStep(
    val label: String,
    val description: String,
    val activeDescription: String,
)

internal val pipelineSteps = listOf(
    PipelineStep("Nahráno", "Audio nahráno na server", "Nahrává se audio..."),
    PipelineStep("Přepis", "Whisper přepsal audio na text", "Whisper přepisuje audio na text..."),
    PipelineStep("Korekce", "LLM model opravil přepis pomocí slovníku", "LLM model opravuje přepis pomocí slovníku..."),
    PipelineStep("Indexace", "Přepis uložen do znalostní báze", "Ukládá se přepis do znalostní báze..."),
)

/** Maps MeetingStateEnum to pipeline step index (0-based) and whether step is active vs done. */
internal fun stateToStepInfo(state: MeetingStateEnum): Pair<Int, Boolean> =
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
internal fun PipelineProgress(
    state: MeetingStateEnum,
    transcriptionPercent: Double? = null,
    correctionProgress: MeetingViewModel.CorrectionProgressInfo? = null,
    stateChangedAt: String? = null,
    lastSegmentText: String? = null,
    onStopTranscription: () -> Unit = {},
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
                state == MeetingStateEnum.INDEXED -> "Zpracování dokončeno"
                // Waiting in queue (step done, next not started yet)
                state == MeetingStateEnum.UPLOADED -> "Ve frontě – čeká na přepis přes Whisper"
                isLikelyStuck && state == MeetingStateEnum.TRANSCRIBING ->
                    "Možná zaseknuto – žádný progress ${elapsedMinutes} min. Zkuste 'Přepsat znovu'."
                state == MeetingStateEnum.TRANSCRIBING && transcriptionPercent != null ->
                    "Whisper přepisuje: ${transcriptionPercent.toInt()}%$elapsedSuffix"
                isLikelyStuck && state == MeetingStateEnum.CORRECTING ->
                    "Možná zaseknuto – žádný progress ${elapsedMinutes} min."
                state == MeetingStateEnum.CORRECTING && correctionProgress != null ->
                    (correctionProgress.message ?: "Korekce: chunk ${correctionProgress.chunksDone}/${correctionProgress.totalChunks}") + elapsedSuffix
                state == MeetingStateEnum.TRANSCRIBED -> "Ve frontě – čeká na korekci přes LLM model"
                state == MeetingStateEnum.CORRECTION_REVIEW -> "Agent potřebuje vaše odpovědi"
                state == MeetingStateEnum.CORRECTED -> "Ve frontě – čeká na indexaci do znalostní báze"
                // Actively processing
                isActive && currentStepIndex in pipelineSteps.indices ->
                    pipelineSteps[currentStepIndex].activeDescription + elapsedSuffix
                else -> null
            }

            if (statusText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.weight(1f),
                    )
                    // Stop button when transcribing
                    if (state == MeetingStateEnum.TRANSCRIBING) {
                        JIconButton(
                            onClick = onStopTranscription,
                            icon = Icons.Default.Stop,
                            contentDescription = "Zastavit přepis",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Last transcribed segment preview — only show during active transcription
            if (!lastSegmentText.isNullOrBlank() && state == MeetingStateEnum.TRANSCRIBING) {
                Text(
                    text = lastSegmentText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
