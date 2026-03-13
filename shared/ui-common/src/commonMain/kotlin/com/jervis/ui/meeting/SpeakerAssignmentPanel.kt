package com.jervis.ui.meeting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton

/**
 * Dialog for assigning speaker profiles to diarization labels in a meeting transcript.
 * Uses custom Dialog (not AlertDialog) to allow full-height scrollable content.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerAssignmentDialog(
    visible: Boolean,
    meeting: MeetingDto,
    speakers: List<SpeakerDto>,
    onAssignSpeakers: (mapping: Map<String, String>) -> Unit,
    onCreateSpeaker: (SpeakerCreateDto) -> Unit,
    onSetVoiceSample: (speakerId: String, voiceSample: VoiceSampleRefDto) -> Unit,
    onSetVoiceEmbedding: (SpeakerEmbeddingDto) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!visible) return

    // Extract unique speaker labels from transcript
    val segments = if (meeting.correctedTranscriptSegments.isNotEmpty())
        meeting.correctedTranscriptSegments else meeting.transcriptSegments
    val uniqueLabels = remember(segments) {
        segments.mapNotNull { it.speaker }.distinct().sorted()
    }

    // Local mapping state (label -> speakerId), initialized from meeting
    val localMapping = remember(meeting.speakerMapping) {
        mutableStateMapOf<String, String>().also { it.putAll(meeting.speakerMapping) }
    }

    // New speaker creation form state
    var showCreateForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newNationality by remember { mutableStateOf("") }
    var newLanguages by remember { mutableStateOf("") }
    var newNotes by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Title row with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Přiřazení mluvčích", style = MaterialTheme.typography.headlineSmall)
                    JIconButton(
                        onClick = onDismiss,
                        icon = Icons.Default.Close,
                        contentDescription = "Zavřít",
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sticky "New Speaker" button — always visible, outside scroll
                JSecondaryButton(
                    onClick = { showCreateForm = !showCreateForm },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showCreateForm) "Skrýt formulář" else "Nový řečník")
                }

                // Inline create form — outside scroll, collapses when hidden
                AnimatedVisibility(visible = showCreateForm) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        JTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = "Jméno",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        JTextField(
                            value = newNationality,
                            onValueChange = { newNationality = it },
                            label = "Národnost",
                            placeholder = "např. Čech, Slovák, Němec",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        JTextField(
                            value = newLanguages,
                            onValueChange = { newLanguages = it },
                            label = "Jazyky",
                            placeholder = "cs, sk, en",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        JTextField(
                            value = newNotes,
                            onValueChange = { newNotes = it },
                            label = "Poznámky pro LLM",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            JPrimaryButton(
                                onClick = {
                                    if (newName.isNotBlank() && meeting.clientId != null) {
                                        onCreateSpeaker(
                                            SpeakerCreateDto(
                                                clientIds = listOf(meeting.clientId!!),
                                                name = newName.trim(),
                                                nationality = newNationality.trim().ifBlank { null },
                                                languagesSpoken = newLanguages.split(",")
                                                    .map { it.trim() }.filter { it.isNotBlank() },
                                                notes = newNotes.trim().ifBlank { null },
                                            ),
                                        )
                                        newName = ""
                                        newNationality = ""
                                        newLanguages = ""
                                        newNotes = ""
                                        showCreateForm = false
                                    }
                                },
                                enabled = newName.isNotBlank(),
                            ) {
                                Text("Vytvořit")
                            }
                            JTextButton(onClick = { showCreateForm = false }) {
                                Text("Zrušit")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable speaker mapping rows only
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uniqueLabels.isEmpty()) {
                        item {
                            Text(
                                text = "Přepis neobsahuje rozlišení řečníků (diarizace).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // Speaker label -> dropdown mapping rows
                        items(uniqueLabels) { label ->
                            val selectedId = localMapping[label]
                            val selectedSpeaker = speakers.find { it.id == selectedId }
                            val autoMatch = meeting.autoSpeakerMapping?.get(label)
                            val dropdownItems = listOf<SpeakerDto?>(null) + speakers

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (autoMatch != null) {
                                            val pct = (autoMatch.confidence * 100).toInt()
                                            val embLabel = autoMatch.matchedEmbeddingLabel
                                            val suffix = if (embLabel != null) " [$embLabel]" else ""
                                            Text(
                                                text = "${autoMatch.speakerName} ($pct%)$suffix",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (autoMatch.confidence > 0.70f)
                                                    MaterialTheme.colorScheme.tertiary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    JDropdown(
                                        items = dropdownItems,
                                        selectedItem = selectedSpeaker,
                                        onItemSelected = { speaker ->
                                            if (speaker != null) {
                                                localMapping[label] = speaker.id
                                            } else {
                                                localMapping.remove(label)
                                            }
                                        },
                                        label = "Řečník",
                                        itemLabel = { it?.name ?: "--- Nepřiřazeno ---" },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                // Voice sample button
                                if (selectedSpeaker != null) {
                                    val firstSegment = segments.firstOrNull { it.speaker == label }
                                    if (firstSegment != null && selectedSpeaker.voiceSampleRef == null) {
                                        JTextButton(
                                            onClick = {
                                                onSetVoiceSample(
                                                    selectedSpeaker.id,
                                                    VoiceSampleRefDto(
                                                        meetingId = meeting.id,
                                                        startSec = firstSegment.startSec,
                                                        endSec = firstSegment.endSec,
                                                    ),
                                                )
                                            },
                                        ) {
                                            Text("Uložit vzorek hlasu")
                                        }
                                    } else if (selectedSpeaker.voiceSampleRef != null) {
                                        Text(
                                            text = "Vzorek hlasu uložen",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons at bottom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JTextButton(onClick = onDismiss) { Text("Zrušit") }
                    Spacer(modifier = Modifier.width(8.dp))
                    JPrimaryButton(
                        onClick = {
                            onAssignSpeakers(localMapping.toMap())
                            // Auto-save voice embeddings
                            val embeddings = meeting.speakerEmbeddings
                            if (embeddings != null) {
                                for ((lbl, speakerId) in localMapping) {
                                    val emb = embeddings[lbl]
                                    if (emb != null) {
                                        onSetVoiceEmbedding(
                                            SpeakerEmbeddingDto(
                                                speakerId = speakerId,
                                                embedding = emb,
                                                label = meeting.title ?: lbl,
                                                meetingId = meeting.id,
                                            ),
                                        )
                                    }
                                }
                            }
                            onDismiss()
                        },
                        enabled = uniqueLabels.isNotEmpty(),
                    ) {
                        Text("Uložit mapování")
                    }
                }
            }
        }
    }
}
