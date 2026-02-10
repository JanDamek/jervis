package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.ui.audio.AudioDevice
import com.jervis.ui.audio.SystemAudioCapability
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

private val meetingTypeLabels = mapOf(
    MeetingTypeEnum.MEETING to "Schůzka",
    MeetingTypeEnum.TASK_DISCUSSION to "Diskuse úkolů",
    MeetingTypeEnum.STANDUP_PROJECT to "Standup projekt",
    MeetingTypeEnum.STANDUP_TEAM to "Standup tým",
    MeetingTypeEnum.INTERVIEW to "Pohovor",
    MeetingTypeEnum.WORKSHOP to "Workshop",
    MeetingTypeEnum.REVIEW to "Review",
    MeetingTypeEnum.OTHER to "Jiné",
)

/**
 * Combined setup dialog for a new recording.
 * Collects client, project, audio device, meeting type, and title — all before recording starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSetupDialog(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    audioDevices: List<AudioDevice>,
    systemAudioCapability: SystemAudioCapability,
    onStart: (clientId: String, projectId: String?, audioInputType: AudioInputType, selectedDevice: AudioDevice?, title: String?, meetingType: MeetingTypeEnum) -> Unit,
    onDismiss: () -> Unit,
) {
    var clientId by remember { mutableStateOf(selectedClientId ?: clients.firstOrNull()?.id ?: "") }
    var projectId by remember { mutableStateOf(selectedProjectId) }
    var selectedDevice by remember { mutableStateOf(audioDevices.firstOrNull()) }
    var captureSystemAudio by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(MeetingTypeEnum.MEETING) }

    JFormDialog(
        visible = true,
        title = "Nová nahrávka",
        onConfirm = {
            val inputType = when {
                captureSystemAudio -> AudioInputType.MIXED
                else -> AudioInputType.MICROPHONE
            }
            onStart(clientId, projectId, inputType, selectedDevice, title.ifBlank { null }, selectedType)
        },
        onDismiss = onDismiss,
        confirmEnabled = clientId.isNotBlank(),
        confirmText = "Zahájit nahrávání",
    ) {
        // Client selector
        var clientExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = clientExpanded,
            onExpandedChange = { clientExpanded = it },
        ) {
            OutlinedTextField(
                value = clients.find { it.id == clientId }?.name ?: "Vyberte klienta...",
                onValueChange = {},
                readOnly = true,
                label = { Text("Klient") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = clientExpanded,
                onDismissRequest = { clientExpanded = false },
            ) {
                clients.forEach { client ->
                    DropdownMenuItem(
                        text = { Text(client.name) },
                        onClick = {
                            clientId = client.id
                            projectId = null
                            clientExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Project selector (optional)
        val filteredProjects = projects.filter { it.clientId == clientId }
        var projectExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
        ) {
            OutlinedTextField(
                value = filteredProjects.find { it.id == projectId }?.name ?: "(volitelné)",
                onValueChange = {},
                readOnly = true,
                label = { Text("Projekt") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                enabled = filteredProjects.isNotEmpty(),
            )
            ExposedDropdownMenu(
                expanded = projectExpanded,
                onDismissRequest = { projectExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("(bez projektu)") },
                    onClick = {
                        projectId = null
                        projectExpanded = false
                    },
                )
                filteredProjects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            projectId = project.id
                            projectExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Title input
        JTextField(
            value = title,
            onValueChange = { title = it },
            label = "Název (volitelné)",
            placeholder = "Název nahrávky...",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Meeting type selector
        Text(
            text = "Typ meetingu:",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Column(modifier = Modifier.selectableGroup()) {
            MeetingTypeEnum.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(JervisSpacing.touchTarget)
                        .selectable(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        meetingTypeLabels[type] ?: type.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Audio device selection
        if (audioDevices.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Zvukový vstup:",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Column(modifier = Modifier.selectableGroup()) {
                audioDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(JervisSpacing.touchTarget)
                            .selectable(
                                selected = selectedDevice == device,
                                onClick = { selectedDevice = device },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedDevice == device,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(device.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // System audio toggle
        when (systemAudioCapability) {
            is SystemAudioCapability.Available -> {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = captureSystemAudio,
                        onCheckedChange = { captureSystemAudio = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Nahrávat i systémový zvuk", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "(reproduktory/sluchátka – zvuk z Teams apod.)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is SystemAudioCapability.RequiresSetup -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = systemAudioCapability.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SystemAudioCapability.NotSupported -> {
                // Don't show anything
            }
        }
    }
}
