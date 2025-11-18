package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.error.ErrorLogDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch
import com.jervis.ui.design.JTopBar

@OptIn(ExperimentalMaterial3Api::class)
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
                errorLogs = repository.errorLogs.listAllErrorLogs(500)
            } catch (e: Exception) {
                errorMessage = "Failed to load error logs: ${e.message}"
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
                title = "Error Logs",
                onBack = onBack,
                actions = {
                    com.jervis.ui.util.RefreshIconButton(onClick = { loadErrorLogs() })
                }
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize()) { com.jervis.ui.design.JCenteredLoading() }
                }
                errorMessage != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        com.jervis.ui.design.JErrorState(
                            message = errorMessage!!,
                            onRetry = { loadErrorLogs() }
                        )
                    }
                }
                errorLogs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        com.jervis.ui.design.JEmptyState(message = "No errors recorded")
                    }
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
                            }
                        )

                        // Details with copy (unified component)
                        val selected = errorLogs.firstOrNull { it.id == selectedLogId }
                        if (selected != null) {
                            Spacer(Modifier.height(8.dp))
                            com.jervis.ui.util.CopyableTextCard(
                                title = "Error details (copy)",
                                content = buildString {
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
    com.jervis.ui.util.ConfirmDialog(
        visible = showDeleteDialog && selectedLogId != null,
        title = "Delete Error Log",
        message = "Are you sure you want to delete this error log? This action cannot be undone.",
        confirmText = "Delete",
        onConfirm = {
            scope.launch {
                try {
                    repository.errorLogs.deleteErrorLog(selectedLogId!!)
                    selectedLogId = null
                    showDeleteDialog = false
                    loadErrorLogs()
                } catch (e: Exception) {
                    errorMessage = "Failed to delete: ${e.message}"
                    showDeleteDialog = false
                }
            }
        },
        onDismiss = { showDeleteDialog = false }
    )
}

@Composable
private fun ErrorLogsTable(
    errorLogs: List<ErrorLogDto>,
    selectedLogId: String?,
    onRowSelected: (String?) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header
        item {
            com.jervis.ui.design.JTableHeaderRow(
                modifier = Modifier.padding(horizontal = 0.dp)
            ) {
                com.jervis.ui.design.JTableHeaderCell("Timestamp", modifier = Modifier.weight(0.25f))
                com.jervis.ui.design.JTableHeaderCell("Message", modifier = Modifier.weight(0.55f))
                com.jervis.ui.design.JTableHeaderCell("Type", modifier = Modifier.weight(0.15f))
                com.jervis.ui.design.JTableHeaderCell("Actions", modifier = Modifier.weight(0.05f))
            }
        }

        // Rows
        items(errorLogs) { log ->
            val isSelected = selectedLogId == log.id
            com.jervis.ui.design.JTableRowCard(
                selected = isSelected,
                modifier = Modifier.fillMaxWidth().clickable {
                    onRowSelected(if (isSelected) null else log.id)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        com.jervis.ui.util.DeleteIconButton(
                            onClick = { onDeleteClick(log.id) }
                        )
                    }
                }
            }
        }
    }
}
