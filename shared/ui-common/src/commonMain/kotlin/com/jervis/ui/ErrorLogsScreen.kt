package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.error.ErrorLogDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JTableHeaderCell
import com.jervis.ui.design.JTableHeaderRow
import com.jervis.ui.design.JTopBar
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.CopyableTextCard
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
fun ErrorLogsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var errorLogs by remember { mutableStateOf<List<ErrorLogDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedLogId by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadErrorLogs() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                errorLogs = repository.errorLogs.listAll()
            } catch (e: Exception) {
                errorMessage = "Chyba při načítání logů: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Load on mount
    LaunchedEffect(Unit) { loadErrorLogs() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "Chybové logy",
                onBack = onBack,
                actions = {
                    RefreshIconButton(onClick = { loadErrorLogs() })
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    JCenteredLoading()
                }

                errorMessage != null -> {
                    JErrorState(
                        message = errorMessage!!,
                        onRetry = { loadErrorLogs() },
                    )
                }

                errorLogs.isEmpty() -> {
                    JEmptyState(message = "Žádné chyby nezaznamenány")
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Error logs table
                        ErrorLogsTable(
                            errorLogs = errorLogs,
                            selectedLogId = selectedLogId,
                            onRowSelected = { selectedLogId = it },
                            onDeleteClick = { logId ->
                                selectedLogId = logId
                                showDeleteDialog = true
                            },
                        )

                        // Details with copy (unified component)
                        val selected = errorLogs.firstOrNull { it.id == selectedLogId }
                        if (selected != null) {
                            Spacer(Modifier.height(8.dp))
                            CopyableTextCard(
                                title = "Detaily chyby (kopírovat)",
                                content =
                                    buildString {
                                        appendLine(selected.message)
                                        selected.stackTrace?.let {
                                            appendLine()
                                            appendLine(it)
                                        }
                                    }.trimEnd(),
                                useMonospace = true,
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    ConfirmDialog(
        visible = showDeleteDialog && selectedLogId != null,
        title = "Smazat log",
        message = "Opravdu chcete smazat tento záznam o chybě? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = {
            scope.launch {
                try {
                    repository.errorLogs.delete(selectedLogId!!)
                    selectedLogId = null
                    showDeleteDialog = false
                    loadErrorLogs()
                } catch (e: Exception) {
                    errorMessage = "Smazání selhalo: ${e.message}"
                    showDeleteDialog = false
                }
            }
        },
        onDismiss = { showDeleteDialog = false },
    )
}

@Composable
private fun ErrorLogsTable(
    errorLogs: List<ErrorLogDto>,
    selectedLogId: String?,
    onRowSelected: (String?) -> Unit,
    onDeleteClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header
        item {
            JTableHeaderRow(
                modifier = Modifier.padding(horizontal = 0.dp),
            ) {
                JTableHeaderCell("Čas", modifier = Modifier.weight(0.25f))
                JTableHeaderCell("Zpráva", modifier = Modifier.weight(0.55f))
                JTableHeaderCell("Typ", modifier = Modifier.weight(0.15f))
                JTableHeaderCell("Akce", modifier = Modifier.weight(0.05f))
            }
        }

        // Rows
        items(errorLogs) { log ->
            val isSelected = selectedLogId == log.id
            JCard(
                selected = isSelected,
                modifier = Modifier.fillMaxWidth().clickable {
                    onRowSelected(if (isSelected) null else log.id)
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = log.createdAt,
                        modifier = Modifier.weight(0.25f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = log.message,
                        modifier = Modifier.weight(0.55f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    log.causeType?.let { causeType ->
                        Text(
                            text = causeType.substringAfterLast('.'),
                            modifier = Modifier.weight(0.15f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    } ?: Spacer(modifier = Modifier.weight(0.15f))

                    // Delete action
                    Box(modifier = Modifier.weight(0.05f), contentAlignment = Alignment.CenterEnd) {
                        DeleteIconButton(
                            onClick = { onDeleteClick(log.id) },
                        )
                    }
                }
            }
        }
    }
}
