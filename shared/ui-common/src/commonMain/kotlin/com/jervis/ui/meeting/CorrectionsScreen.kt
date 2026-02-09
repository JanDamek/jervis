package com.jervis.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JEmptyState

private val CATEGORIES = listOf(
    "person_name" to "Jmena osob",
    "company_name" to "Nazvy firem",
    "department" to "Oddeleni",
    "terminology" to "Terminologie",
    "abbreviation" to "Zkratky",
    "general" to "Obecne opravy",
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
        JEmptyState("Sluzba korekci neni dostupna")
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
        title = "Korekce prepisu",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                dialogOriginal = ""
                showAddDialog = true
            }) {
                Text("+ Pridat")
            }
        },
    ) {
        if (isLoading) {
            JCenteredLoading()
        } else if (corrections.isEmpty()) {
            JEmptyState("Zadne korekce. Pridejte pravidla pro opravu prepisu.")
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(),
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
                        text = correction.context,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Text("\uD83D\uDDD1", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CorrectionDialog(
    initialOriginal: String = "",
    onConfirm: (TranscriptCorrectionSubmitDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var original by remember { mutableStateOf(initialOriginal) }
    var corrected by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }
    var context by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pridat korekci") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text("Spatne prepsano") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = corrected,
                    onValueChange = { corrected = it },
                    label = { Text("Spravny tvar") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it },
                ) {
                    OutlinedTextField(
                        value = CATEGORIES.firstOrNull { it.first == category }?.second ?: category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kategorie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                    ) {
                        CATEGORIES.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    category = key
                                    categoryExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it },
                    label = { Text("Kontext (volitelne)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
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
                enabled = original.isNotBlank() && corrected.isNotBlank(),
            ) {
                Text("Ulozit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrusit")
            }
        },
    )
}
