package com.jervis.ui.environment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JTextButton
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.LocalJervisSemanticColors

/**
 * Environment panel showing all environments for the current client as an expandable tree.
 * Used as a side panel on expanded layouts or full-screen on compact layouts.
 */
@Composable
fun EnvironmentPanel(
    environments: List<EnvironmentDto>,
    statuses: Map<String, EnvironmentStatusDto>,
    resolvedEnvId: String?,
    isLoading: Boolean,
    error: String?,
    isCompact: Boolean,
    expandedEnvIds: Set<String>,
    expandedComponentIds: Set<String>,
    onToggleEnv: (String) -> Unit,
    onToggleComponent: (String) -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenInManager: (String) -> Unit = {},
    onDeploy: (String) -> Unit = {},
    onStop: (String) -> Unit = {},
    onViewLogs: (String, String) -> Unit = { _, _ -> },
    logViewState: EnvironmentViewModel.LogViewState? = null,
    onCloseLogView: () -> Unit = {},
    activeEnvironmentSummary: String? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(
            title = "Prostředí",
            onBack = if (isCompact) onClose else null,
            actions = {
                JRefreshButton(onClick = onRefresh)
                IconButton(onClick = { onOpenInManager("") }) {
                    Icon(Icons.Default.Settings, contentDescription = "Spravovat prostředí")
                }
                if (!isCompact) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Zavřít")
                    }
                }
            },
        )

        // Chat context indicator — shows which environment the chat/agent is aware of
        if (activeEnvironmentSummary != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val semanticColors = LocalJervisSemanticColors.current
                Surface(
                    modifier = Modifier.size(6.dp),
                    shape = CircleShape,
                    color = semanticColors.success,
                ) {}
                Spacer(Modifier.width(6.dp))
                Text(
                    "Chat kontext: $activeEnvironmentSummary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                )
            }
        }

        when {
            isLoading && environments.isEmpty() -> {
                JCenteredLoading()
            }
            error != null && environments.isEmpty() -> {
                JErrorState(message = error, onRetry = onRefresh)
            }
            environments.isEmpty() -> {
                JEmptyState(message = "Žádná prostředí", icon = Icons.Default.Dns)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(environments, key = { it.id }) { env ->
                        EnvironmentTreeNode(
                            environment = env,
                            status = statuses[env.id],
                            isResolved = env.id == resolvedEnvId,
                            expanded = expandedEnvIds.contains(env.id),
                            onToggleExpand = { onToggleEnv(env.id) },
                            expandedComponentIds = expandedComponentIds,
                            onToggleComponent = onToggleComponent,
                            onManage = onOpenInManager,
                            onDeploy = onDeploy,
                            onStop = onStop,
                            onViewLogs = onViewLogs,
                        )
                    }
                }
            }
        }
    }

    // Log viewer dialog
    if (logViewState != null) {
        AlertDialog(
            onDismissRequest = onCloseLogView,
            title = { Text("Logy: ${logViewState.componentName}", style = MaterialTheme.typography.titleMedium) },
            text = {
                if (logViewState.logs == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Načítání logů…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = logViewState.logs,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 400.dp),
                        )
                    }
                }
            },
            confirmButton = {
                JTextButton(onClick = onCloseLogView) { Text("Zavřít") }
            },
        )
    }
}
