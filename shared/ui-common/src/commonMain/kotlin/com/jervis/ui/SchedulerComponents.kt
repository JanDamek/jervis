package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun ScheduledTaskDetail(
    task: EnhancedScheduledTask,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    JDetailScreen(
        title = task.task.taskName,
        onBack = onBack,
        actions = {
            JDestructiveButton(onClick = onDelete) {
                Text("Smazat")
            }
        },
    ) {
        val scrollState = rememberScrollState()

        SelectionContainer {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Základní údaje") {
                    JKeyValueRow("Název úlohy", task.task.taskName)
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JKeyValueRow("Klient", task.clientName)
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JKeyValueRow("Projekt", task.projectName)
                    Spacer(Modifier.height(JervisSpacing.itemGap))
                    JKeyValueRow("Naplánováno", formatInstant(task.task.scheduledAt))
                    task.task.cronExpression?.let {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JKeyValueRow("Cron výraz", it)
                    }
                    task.task.correlationId?.let {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        JKeyValueRow("Correlation ID", it)
                    }
                }

                JSection(title = "Instrukce pro agenta") {
                    Text(
                        text = task.task.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun ScheduleTaskDialog(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    onDismiss: () -> Unit,
    onCreate: (clientId: String, projectId: String?, taskName: String, content: String, cronExpression: String?) -> Unit,
) {
    var selectedClient by remember { mutableStateOf(clients.firstOrNull()) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var taskName by remember { mutableStateOf("") }
    var taskInstruction by remember { mutableStateOf("") }
    var cronExpression by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    // Auto-select first project for selected client
    LaunchedEffect(selectedClient) {
        val clientProjects = projects.filter { it.clientId == selectedClient?.id }
        selectedProject = clientProjects.firstOrNull()
    }

    val clientProjects = projects.filter { it.clientId == selectedClient?.id }
    val enabled = selectedClient != null && taskInstruction.isNotBlank() && !isSaving

    JFormDialog(
        visible = true,
        title = "Naplánovat úlohu",
        onConfirm = {
            val client = selectedClient ?: return@JFormDialog
            val finalName = taskName.ifBlank { "Úloha: ${taskInstruction.take(50)}" }
            isSaving = true
            onCreate(
                client.id,
                selectedProject?.id,
                finalName,
                taskInstruction,
                cronExpression.ifBlank { null },
            )
        },
        onDismiss = onDismiss,
        confirmEnabled = enabled,
        confirmText = "Naplánovat",
    ) {
        JDropdown(
            items = clients,
            selectedItem = selectedClient,
            onItemSelected = { selectedClient = it },
            label = "Klient",
            itemLabel = { it.name },
        )
        Spacer(Modifier.height(12.dp))
        JDropdown(
            items = clientProjects,
            selectedItem = selectedProject,
            onItemSelected = { selectedProject = it },
            label = "Projekt (volitelné)",
            itemLabel = { it.name },
        )
        Spacer(Modifier.height(12.dp))
        JTextField(
            value = taskName,
            onValueChange = { taskName = it },
            label = "Název úlohy (volitelné)",
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        JTextField(
            value = taskInstruction,
            onValueChange = { taskInstruction = it },
            label = "Instrukce pro agenta",
            singleLine = false,
            minLines = 4,
        )
        Spacer(Modifier.height(12.dp))
        JTextField(
            value = cronExpression,
            onValueChange = { cronExpression = it },
            label = "Cron výraz (volitelné)",
            placeholder = "např. 0 0 * * *",
            singleLine = true,
        )
    }
}

internal fun formatInstant(epochMillis: Long): String {
    return try {
        val instant = Instant.fromEpochMilliseconds(epochMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.dayOfMonth}.${local.monthNumber}.${local.year} " +
            "${local.hour.toString().padStart(2, '0')}:" +
            "${local.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        epochMillis.toString()
    }
}
