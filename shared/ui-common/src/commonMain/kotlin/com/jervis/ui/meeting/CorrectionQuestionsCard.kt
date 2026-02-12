package com.jervis.ui.meeting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton

/**
 * Card showing correction questions from the agent.
 * Each question can be confirmed individually (collapses to compact view).
 * Only the final "Odeslat vše" button sends all confirmed answers to the backend.
 * Card is height-limited and scrollable when there are many questions.
 */
@Composable
internal fun CorrectionQuestionsCard(
    questions: List<CorrectionQuestionDto>,
    onSubmitAnswers: (List<CorrectionAnswerDto>) -> Unit,
    segments: List<TranscriptSegmentDto> = emptyList(),
    playingSegmentIndex: Int = -1,
    onSegmentPlay: (segmentIndex: Int, startSec: Double, endSec: Double) -> Unit = { _, _, _ -> },
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
                            isPlayingSegment = playingSegmentIndex == question.segmentIndex,
                            onPlayToggle = {
                                val seg = segments.getOrNull(question.segmentIndex)
                                if (seg != null) {
                                    val paddedStart = (seg.startSec - 10.0).coerceAtLeast(0.0)
                                    val paddedEnd = seg.endSec + 10.0
                                    onSegmentPlay(question.segmentIndex, paddedStart, paddedEnd)
                                }
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
                    JTextButton(
                        onClick = {
                            val answerDtos = questions.mapNotNull { q ->
                                if (q.questionId !in confirmed.value) return@mapNotNull null
                                val rawAnswer = answers.value[q.questionId]?.trim()
                                val isNevim = rawAnswer == "\u0000"
                                if (isNevim) {
                                    // "Nevím" → send empty corrected string
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

@Composable
private fun CorrectionQuestionItem(
    question: CorrectionQuestionDto,
    currentAnswer: String,
    isConfirmed: Boolean,
    onAnswerChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    isPlayingSegment: Boolean = false,
    onPlayToggle: () -> Unit = {},
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                JIconButton(
                    onClick = onPlayToggle,
                    icon = if (isPlayingSegment) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlayingSegment) "Zastavit" else "Přehrát",
                    tint = if (isPlayingSegment) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                JTextField(
                    value = if (isNevim) "" else currentAnswer,
                    onValueChange = onAnswerChanged,
                    label = "Správný tvar",
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                JSecondaryButton(
                    onClick = {
                        onAnswerChanged("\u0000")
                        onConfirm()
                    },
                ) {
                    Text("Nevím")
                }
                JTextButton(
                    onClick = onConfirm,
                    enabled = currentAnswer.isNotBlank() && !isNevim,
                ) {
                    Text("Potvrdit")
                }
            }
        }
    }
}
