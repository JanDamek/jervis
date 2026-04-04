package com.jervis.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jervis.di.JervisRepository
import com.jervis.dto.timetracking.CapacitySnapshotDto
import com.jervis.dto.timetracking.TimeEntryCreateDto
import com.jervis.dto.timetracking.TimeEntryDto
import com.jervis.dto.timetracking.TimeSummaryDto
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.*
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon

private enum class TimeCategory(
    val title: String,
    val icon: ImageVector,
    val description: String,
) {
    TODAY("Dnes", Icons.Default.AccessTime, "Dnešní záznamy a rychlý zápis."),
    SUMMARY("Přehled", Icons.Default.BarChart, "Souhrn odpracovaného času."),
    CAPACITY("Kapacita", Icons.Default.Speed, "Kapacitní přehled a dostupnost."),
}

@Composable
fun TimeTrackingScreen(
    repository: JervisRepository,
    selectedClientId: String?,
    onBack: () -> Unit,
) {
    val categories = remember { TimeCategory.entries.toList() }
    var selectedIndex by remember { mutableStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Čas a kapacita",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category ->
            when (category) {
                TimeCategory.TODAY -> TodaySection(repository, selectedClientId)
                TimeCategory.SUMMARY -> SummarySection(repository, selectedClientId)
                TimeCategory.CAPACITY -> CapacitySection(repository)
            }
        },
    )
}

// ── Today Section ──

@Composable
private fun TodaySection(repository: JervisRepository, clientId: String?) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var entries by remember { mutableStateOf<List<TimeEntryDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    suspend fun loadEntries() {
        isLoading = true
        try {
            entries = repository.timeTracking.getTodayEntries()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) { loadEntries() }

    Box {
        Column {
            JActionBar {
                val totalToday = entries.sumOf { it.hours }
                Text(
                    "Dnes: ${formatHours(totalToday)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                JPrimaryButton(
                    text = "Zapsat čas",
                    icon = Icons.Default.Add,
                    onClick = { showLogDialog = true },
                )
            }

            if (isLoading) {
                JCenteredLoading()
            } else if (entries.isEmpty()) {
                JEmptyState("Žádné záznamy pro dnešek.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TimeEntryCard(entry, onDelete = {
                            scope.launch {
                                try {
                                    repository.timeTracking.deleteTimeEntry(entry.id)
                                    loadEntries()
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        })
                    }
                }
            }
        }

        if (showLogDialog) {
            LogTimeDialog(
                clientId = clientId,
                onDismiss = { showLogDialog = false },
                onSave = { dto ->
                    scope.launch {
                        try {
                            repository.timeTracking.logTime(dto)
                            showLogDialog = false
                            loadEntries()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Chyba: ${e.message}")
                        }
                    }
                },
            )
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun TimeEntryCard(entry: TimeEntryDto, onDelete: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.description.ifBlank { "(bez popisu)" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${entry.source.name} | ${entry.date}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatHours(entry.hours),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            JIconButton(
                icon = Icons.Default.Delete,
                contentDescription = "Smazat",
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun LogTimeDialog(
    clientId: String?,
    onDismiss: () -> Unit,
    onSave: (TimeEntryCreateDto) -> Unit,
) {
    var hours by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var entryClientId by remember { mutableStateOf(clientId ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zapsat čas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it },
                    label = { Text("Hodiny") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis práce") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = entryClientId,
                    onValueChange = { entryClientId = it },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Datum (YYYY-MM-DD, default dnes)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedHours = hours.toDoubleOrNull() ?: return@TextButton
                    if (entryClientId.isBlank()) return@TextButton
                    onSave(
                        TimeEntryCreateDto(
                            clientId = entryClientId,
                            hours = parsedHours,
                            description = description,
                            date = date.ifBlank { null },
                        ),
                    )
                },
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

// ── Summary Section ──

@Composable
private fun SummarySection(repository: JervisRepository, clientId: String?) {
    val snackbarHostState = remember { SnackbarHostState() }
    var summary by remember { mutableStateOf<TimeSummaryDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration, clientId) {
        isLoading = true
        try {
            summary = repository.timeTracking.getTimeSummary(clientId = clientId)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Box {
        if (isLoading) {
            JCenteredLoading()
        } else if (summary == null) {
            JEmptyState("Žádná data o času.")
        } else {
            val s = summary!!
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Tento měsíc") {
                    SummaryRow("Celkový čas", formatHours(s.totalHours))
                    SummaryRow("Fakturovatelný čas", formatHours(s.billableHours))
                }
                if (s.byClient.isNotEmpty()) {
                    JSection(title = "Po klientech") {
                        s.byClient.forEach { (cid, hours) ->
                            SummaryRow(cid, formatHours(hours))
                        }
                    }
                }
            }
        }
        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

// ── Capacity Section ──

@Composable
private fun CapacitySection(repository: JervisRepository) {
    val snackbarHostState = remember { SnackbarHostState() }
    var capacity by remember { mutableStateOf<CapacitySnapshotDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) {
        isLoading = true
        try {
            capacity = repository.timeTracking.getCapacity()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Box {
        if (isLoading) {
            JCenteredLoading()
        } else if (capacity == null) {
            JEmptyState("Žádná kapacitní data.")
        } else {
            val c = capacity!!
            val usedPercent = if (c.totalHoursPerWeek > 0) {
                ((c.totalHoursPerWeek - c.availableHours) / c.totalHoursPerWeek).toFloat().coerceIn(0f, 1f)
            } else 0f

            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Týdenní kapacita") {
                    SummaryRow("Celkem", "${c.totalHoursPerWeek.toInt()}h/týden")
                    SummaryRow("Dostupné", formatHours(c.availableHours))
                    LinearProgressIndicator(
                        progress = { usedPercent },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = if (usedPercent > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${(usedPercent * 100).toInt()}% kapacity commitováno",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (c.committed.isNotEmpty()) {
                    JSection(title = "Commitované ze smluv") {
                        c.committed.forEach { cap ->
                            SummaryRow(cap.counterparty, "${cap.hoursPerWeek.toInt()}h/týden")
                        }
                    }
                }

                if (c.actualThisWeek.isNotEmpty()) {
                    JSection(title = "Odpracováno tento týden") {
                        c.actualThisWeek.forEach { (cid, hours) ->
                            SummaryRow(cid, formatHours(hours))
                        }
                    }
                }
            }
        }
        JSnackbarHost(snackbarHostState)
    }
}

private fun formatHours(hours: Double): String {
    return if (hours == hours.toLong().toDouble()) {
        "${hours.toLong()}h"
    } else {
        "${kotlin.math.round(hours * 10) / 10.0}h"
    }
}
