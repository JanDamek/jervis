package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.jervis.dto.meeting.TranscriptCorrectionDto
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.service.ITranscriptCorrectionService
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JFormDialog
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JTextButton
import com.jervis.ui.util.DeleteIconButton

private val CATEGORIES = listOf(
    "person_name" to "Jména osob",
    "company_name" to "Názvy firem",
    "department" to "Oddělení",
    "terminology" to "Terminologie",
    "abbreviation" to "Zkratky",
    "general" to "Obecné opravy",
)

/**
 * Corrections management screen.
 * Shows KB-stored transcript corrections with CRUD.
 */
@Composable
fun CorrectionsScreen(
    correctionService: ITranscriptCorrectionService?,
    clientId: String,
    projectId: String?,
    onBack: () -> Unit,
    prefilledOriginal: String? = null,
) {
    if (correctionService == null) {
        JEmptyState("Služba korekcí není dostupná")
        return
    }

    val viewModel = remember { CorrectionViewModel(correctionService) }
    val corrections by viewModel.corrections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showAddDialog by remember { mutableStateOf(prefilledOriginal != null) }
    var dialogOriginal by remember { mutableStateOf(prefilledOriginal ?: "") }

    LaunchedEffect(clientId, projectId) {
        viewModel.loadCorrections(clientId, projectId)
    }

    JDetailScreen(
        title = "Korekce přepisu",
        onBack = onBack,
        actions = {
            JTextButton(onClick = {
                dialogOriginal = ""
                showAddDialog = true
            }) {
                Text("+ Přidat")
            }
        },
    ) {
        if (isLoading) {
            JCenteredLoading()
        } else if (corrections.isEmpty()) {
            JEmptyState("Žádné korekce. Přidejte pravidla pro opravu přepisu.")
        } else {
            val grouped = corrections.groupBy { it.category }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((category, entries) in grouped) {
                    val label = CATEGORIES.firstOrNull { it.first == category }?.second ?: category
                    item {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(entries, key = { it.sourceUrn }) { correction ->
                        CorrectionCard(
                            correction = correction,
                            onDelete = { viewModel.deleteCorrection(correction.sourceUrn, clientId, projectId) },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CorrectionDialog(
            initialOriginal = dialogOriginal,
            onConfirm = { submit ->
                viewModel.submitCorrection(submit, clientId, projectId)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun CorrectionCard(
    correction: TranscriptCorrectionDto,
    onDelete: () -> Unit,
) {
    JCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\"${correction.original}\" \u2192 \"${correction.corrected}\"",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!correction.context.isNullOrBlank()) {
                    Text(
                        text = correction.context.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DeleteIconButton(onClick = onDelete)
        }
    }
}

@Composable
internal fun CorrectionDialog(
    initialOriginal: String = "",
    initialCorrected: String = "",
    onConfirm: (TranscriptCorrectionSubmitDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var original by remember { mutableStateOf(initialOriginal) }
    var corrected by remember { mutableStateOf(initialCorrected) }
    var category by remember { mutableStateOf("general") }
    var context by remember { mutableStateOf("") }

    JFormDialog(
        visible = true,
        title = "Přidat korekci",
        onConfirm = {
            if (original.isNotBlank() && corrected.isNotBlank()) {
                onConfirm(
                    TranscriptCorrectionSubmitDto(
                        clientId = "",
                        original = original.trim(),
                        corrected = corrected.trim(),
                        category = category,
                        context = context.ifBlank { null },
                    ),
                )
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = original.isNotBlank() && corrected.isNotBlank(),
        confirmText = "Uložit",
    ) {
        JTextField(
            value = original,
            onValueChange = { original = it },
            label = "Špatně přepsáno",
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        JTextField(
            value = corrected,
            onValueChange = { corrected = it },
            label = "Správný tvar",
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        JDropdown(
            items = CATEGORIES,
            selectedItem = CATEGORIES.firstOrNull { it.first == category },
            onItemSelected = { category = it.first },
            label = "Kategorie",
            itemLabel = { it.second },
        )
        Spacer(Modifier.height(8.dp))
        JTextField(
            value = context,
            onValueChange = { context = it },
            label = "Kontext (volitelné)",
            singleLine = false,
            minLines = 2,
            maxLines = 3,
        )
    }
}
