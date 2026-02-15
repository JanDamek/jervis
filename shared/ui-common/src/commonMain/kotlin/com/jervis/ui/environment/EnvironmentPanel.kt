package com.jervis.ui.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JTopBar

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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        JTopBar(
            title = "Prostředí",
            onBack = if (isCompact) onClose else null,
            actions = {
                JRefreshButton(onClick = onRefresh)
                if (!isCompact) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Zavřít")
                    }
                }
            },
        )

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
                        )
                    }
                }
            }
        }
    }
}
