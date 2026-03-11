package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton

/**
 * Panel for assigning speaker profiles to diarization labels in a meeting transcript.
 * Shows unique speaker labels from transcript, dropdown to pick/create speakers,
 * and voice sample extraction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerAssignmentPanel(
    meeting: MeetingDto,
    speakers: List<SpeakerDto>,
    onAssignSpeakers: (mapping: Map<String, String>) -> Unit,
    onCreateSpeaker: (SpeakerCreateDto) -> Unit,
    onSetVoiceSample: (speakerId: String, voiceSample: VoiceSampleRefDto) -> Unit,
    onSetVoiceEmbedding: (SpeakerEmbeddingDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Extract unique speaker labels from transcript
    val segments = if (meeting.correctedTranscriptSegments.isNotEmpty())
        meeting.correctedTranscriptSegments else meeting.transcriptSegments
    val uniqueLabels = remember(segments) {
        segments.mapNotNull { it.speaker }.distinct().sorted()
    }

    // Local mapping state (label -> speakerId), initialized from meeting
    val localMapping = remember(meeting.speakerMapping) { mutableStateMapOf<String, String>().also { it.putAll(meeting.speakerMapping) } }

    // New speaker creation form state
    var showCreateForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newNationality by remember { mutableStateOf("") }
    var newLanguages by remember { mutableStateOf("") }
    var newNotes by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Mluvci",
            style = MaterialTheme.typography.titleMedium,
        )

        if (uniqueLabels.isEmpty()) {
            Text(
                text = "Přepis neobsahuje rozlišení řečníků (diarizace).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Dropdown selector for each speaker label
            uniqueLabels.forEach { label ->
                val selectedId = localMapping[label]
                val selectedSpeaker = speakers.find { it.id == selectedId }
                val autoMatch = meeting.autoSpeakerMapping?.get(label)

                // Build items: existing speakers + null for "not assigned"
                val dropdownItems = listOf<SpeakerDto?>(null) + speakers

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
                        // Show auto-match confidence badge with matched embedding label
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

                // Voice sample button (only when speaker is assigned)
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

            HorizontalDivider()

            // Save mapping button — also auto-saves voice embeddings
            JPrimaryButton(
                onClick = {
                    onAssignSpeakers(localMapping.toMap())
                    // Auto-save voice embeddings — always add (multi-embedding, different conditions)
                    val embeddings = meeting.speakerEmbeddings
                    if (embeddings != null) {
                        for ((lbl, speakerId) in localMapping) {
                            val emb = embeddings[lbl]
                            if (emb != null) {
                                onSetVoiceEmbedding(SpeakerEmbeddingDto(
                                    speakerId = speakerId,
                                    embedding = emb,
                                    label = meeting.title ?: lbl,
                                    meetingId = meeting.id,
                                ))
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Uložit mapování")
            }
        }

        HorizontalDivider()

        // Create new speaker section
        if (!showCreateForm) {
            JSecondaryButton(
                onClick = { showCreateForm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Nový řečník")
            }
        } else {
            Text(
                text = "Nový řečník",
                style = MaterialTheme.typography.titleSmall,
            )
            JTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "Jméno",
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = newNationality,
                onValueChange = { newNationality = it },
                label = "Národnost (např. Slovák, Čech, Němec)",
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = newLanguages,
                onValueChange = { newLanguages = it },
                label = "Jazyky (kódy oddělené čárkou, např. cs,sk,en)",
                modifier = Modifier.fillMaxWidth(),
            )
            JTextField(
                value = newNotes,
                onValueChange = { newNotes = it },
                label = "Poznámky pro LLM",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JPrimaryButton(
                    onClick = {
                        if (newName.isNotBlank() && meeting.clientId != null) {
                            onCreateSpeaker(
                                SpeakerCreateDto(
                                    clientId = meeting.clientId!!,
                                    name = newName.trim(),
                                    nationality = newNationality.trim().ifBlank { null },
                                    languagesSpoken = newLanguages.split(",").map { it.trim() }.filter { it.isNotBlank() },
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
                JTextButton(
                    onClick = { showCreateForm = false },
                ) {
                    Text("Zrušit")
                }
            }
        }
    }
}
