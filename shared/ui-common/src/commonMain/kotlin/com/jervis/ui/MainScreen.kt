package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.design.JIconButton
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.navigation.Screen
import com.jervis.ui.util.PickedFile

/**
 * Main menu items for the dropdown menu.
 * Each item navigates to a separate screen.
 */
private enum class MainMenuItem(val icon: ImageVector, val title: String) {
    SETTINGS(Icons.Default.Settings, "Nastavení"),
    USER_TASKS(Icons.AutoMirrored.Filled.List, "Uživatelské úlohy"),
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta úloh"),
    SCHEDULER(Icons.Default.CalendarMonth, "Plánovač"),
    MEETINGS(Icons.Default.Mic, "Meetingy"),
    RAG_SEARCH(Icons.Default.Search, "RAG Hledání"),
    INDEXING_QUEUE(Icons.Filled.Schedule, "Fronta indexace"),
    ENVIRONMENT_VIEWER(Icons.Default.Cloud, "Prostředí K8s"),
    ERROR_LOGS(Icons.Default.BugReport, "Chybové logy"),
}

private fun MainMenuItem.toScreen(): Screen = when (this) {
    MainMenuItem.SETTINGS -> Screen.Settings
    MainMenuItem.USER_TASKS -> Screen.UserTasks
    MainMenuItem.PENDING_TASKS -> Screen.PendingTasks
    MainMenuItem.SCHEDULER -> Screen.Scheduler
    MainMenuItem.MEETINGS -> Screen.Meetings
    MainMenuItem.RAG_SEARCH -> Screen.RagSearch
    MainMenuItem.INDEXING_QUEUE -> Screen.IndexingQueue
    MainMenuItem.ENVIRONMENT_VIEWER -> Screen.EnvironmentViewer
    MainMenuItem.ERROR_LOGS -> Screen.ErrorLogs
}

/**
 * Main screen for Jervis – unified layout for all screen sizes.
 * No sidebar; menu is accessible via dropdown in the selectors row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenView(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<com.jervis.dto.ProjectGroupDto> = emptyList(),
    selectedClientId: String?,
    selectedProjectId: String?,
    selectedGroupId: String? = null,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    queueSize: Int = 0,
    runningProjectId: String? = null,
    runningProjectName: String? = null,
    runningTaskPreview: String? = null,
    runningTaskType: String? = null,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    attachments: List<PickedFile> = emptyList(),
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onGroupSelected: (String) -> Unit = {},
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onNavigate: (Screen) -> Unit = {},
    onAgentStatusClick: () -> Unit = {},
    connectionState: MainViewModel.ConnectionState = MainViewModel.ConnectionState.CONNECTED,
    onReconnect: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        ChatContent(
            clients = clients,
            projects = projects,
            projectGroups = projectGroups,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            selectedGroupId = selectedGroupId,
            chatMessages = chatMessages,
            inputText = inputText,
            isLoading = isLoading,
            queueSize = queueSize,
            runningProjectId = runningProjectId,
            runningProjectName = runningProjectName,
            runningTaskPreview = runningTaskPreview,
            runningTaskType = runningTaskType,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            compressionBoundaries = compressionBoundaries,
            attachments = attachments,
            onClientSelected = onClientSelected,
            onProjectSelected = onProjectSelected,
            onGroupSelected = onGroupSelected,
            onInputChanged = onInputChanged,
            onSendClick = onSendClick,
            onAgentStatusClick = onAgentStatusClick,
            onNavigate = onNavigate,
            connectionState = connectionState,
            onReconnect = onReconnect,
            onEditMessage = onEditMessage,
            onLoadMore = onLoadMore,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
            pendingMessageInfo = pendingMessageInfo,
            onRetryPending = onRetryPending,
            onCancelPending = onCancelPending,
            workspaceInfo = workspaceInfo,
            onRetryWorkspace = onRetryWorkspace,
            orchestratorHealthy = orchestratorHealthy,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Chat content area – selectors, messages, agent status, input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<com.jervis.dto.ProjectGroupDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    selectedGroupId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    queueSize: Int,
    runningProjectId: String?,
    runningProjectName: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    compressionBoundaries: List<CompressionBoundaryDto>,
    attachments: List<PickedFile>,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onGroupSelected: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onAgentStatusClick: () -> Unit,
    onNavigate: (Screen) -> Unit,
    connectionState: MainViewModel.ConnectionState = MainViewModel.ConnectionState.CONNECTED,
    onReconnect: () -> Unit = {},
    onEditMessage: (String) -> Unit,
    onLoadMore: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Client and Project Selectors + Menu
        SelectorsRow(
            clients = clients,
            projects = projects,
            projectGroups = projectGroups,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            selectedGroupId = selectedGroupId,
            onClientSelected = onClientSelected,
            onProjectSelected = onProjectSelected,
            onGroupSelected = onGroupSelected,
            onNavigate = onNavigate,
            connectionState = connectionState,
            onReconnect = onReconnect,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // Workspace status banner
        if (workspaceInfo != null) {
            WorkspaceBanner(
                info = workspaceInfo,
                onRetry = onRetryWorkspace,
            )
        }

        // Orchestrator health banner
        if (!orchestratorHealthy) {
            OrchestratorHealthBanner()
        }

        // Chat area
        ChatArea(
            messages = chatMessages,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            compressionBoundaries = compressionBoundaries,
            onLoadMore = onLoadMore,
            onEditMessage = onEditMessage,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        // Agent status row – clickable, navigates to workload detail
        AgentStatusRow(
            runningProjectId = runningProjectId,
            runningProjectName = runningProjectName,
            runningTaskPreview = runningTaskPreview,
            runningTaskType = runningTaskType,
            queueSize = queueSize,
            onClick = onAgentStatusClick,
        )

        // Pending message banner — shown when message failed to send
        if (pendingMessageInfo != null) {
            PendingMessageBanner(
                info = pendingMessageInfo,
                onRetry = onRetryPending,
                onCancel = onCancelPending,
            )
        }

        HorizontalDivider()

        // Input area
        InputArea(
            inputText = inputText,
            onInputChanged = onInputChanged,
            onSendClick = onSendClick,
            enabled = selectedClientId != null && selectedProjectId != null && !isLoading,
            queueSize = queueSize,
            runningProjectId = runningProjectId,
            currentProjectId = selectedProjectId,
            attachments = attachments,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorsRow(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<com.jervis.dto.ProjectGroupDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    selectedGroupId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onGroupSelected: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    connectionState: MainViewModel.ConnectionState = MainViewModel.ConnectionState.CONNECTED,
    onReconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Client selector
        var clientExpanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = clientExpanded,
            onExpandedChange = { clientExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = clients.find { it.id == selectedClientId }?.name ?: "Vyberte klienta...",
                onValueChange = {},
                readOnly = true,
                label = { Text("Klient") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientExpanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )

            ExposedDropdownMenu(
                expanded = clientExpanded,
                onDismissRequest = { clientExpanded = false },
            ) {
                clients.forEach { client ->
                    DropdownMenuItem(
                        text = { Text(client.name) },
                        onClick = {
                            onClientSelected(client.id)
                            clientExpanded = false
                        },
                    )
                }
            }
        }

        // Project / Group selector
        var projectExpanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            // Display selected project or group name
            val displayText = when {
                selectedProjectId != null -> projects.find { it.id == selectedProjectId }?.name ?: "Vyberte projekt..."
                selectedGroupId != null -> {
                    val group = projectGroups.find { it.id == selectedGroupId }
                    if (group != null) "📁 ${group.name}" else "Vyberte projekt..."
                }
                else -> "Vyberte projekt..."
            }

            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Projekt / Skupina") },
                enabled = selectedClientId != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )

            ExposedDropdownMenu(
                expanded = projectExpanded,
                onDismissRequest = { projectExpanded = false },
            ) {
                // Show projects first (only ungrouped projects or all projects)
                val ungroupedProjects = projects.filter { it.groupId == null }
                if (ungroupedProjects.isNotEmpty()) {
                    ungroupedProjects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = {
                                onProjectSelected(project.id)
                                projectExpanded = false
                            },
                        )
                    }
                }

                // Show separator if we have both projects and groups
                if (ungroupedProjects.isNotEmpty() && projectGroups.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Show project groups
                if (projectGroups.isNotEmpty()) {
                    projectGroups.forEach { group ->
                        DropdownMenuItem(
                            text = { Text("📁 ${group.name}") },
                            onClick = {
                                onGroupSelected(group.id)
                                projectExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Connection status indicator
        ConnectionStatusIndicator(
            connectionState = connectionState,
            onReconnect = onReconnect,
        )

        // Menu dropdown
        Box {
            var menuExpanded by remember { mutableStateOf(false) }

            JIconButton(
                onClick = { menuExpanded = true },
                icon = Icons.Default.Menu,
                contentDescription = "Menu",
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                MainMenuItem.entries.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(item.title)
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onNavigate(item.toScreen())
                        },
                    )
                }
            }
        }
    }
}

/**
 * Connection status indicator with manual reconnect button.
 * Shows green dot for connected, yellow for connecting, red for disconnected.
 */
@Composable
private fun ConnectionStatusIndicator(
    connectionState: MainViewModel.ConnectionState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when (connectionState) {
        MainViewModel.ConnectionState.CONNECTED -> "CONNECTED"
        MainViewModel.ConnectionState.CONNECTING -> "CONNECTING"
        MainViewModel.ConnectionState.RECONNECTING -> "CONNECTING"
        MainViewModel.ConnectionState.DISCONNECTED -> "DISCONNECTED"
    }

    Box(modifier = modifier) {
        if (connectionState == MainViewModel.ConnectionState.DISCONNECTED) {
            // Show reconnect button when disconnected
            JIconButton(
                onClick = onReconnect,
                icon = Icons.Default.Refresh,
                contentDescription = "Reconnect",
            )
        } else {
            // Just show status badge
            com.jervis.ui.design.JStatusBadge(status = statusText)
        }
    }
}

/**
 * Banner shown when a message failed to send and is pending retry.
 * Shows truncated message text, retry countdown, and Retry/Cancel buttons.
 */
@Composable
private fun PendingMessageBanner(
    info: PendingMessageInfo,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Zpráva nebyla odeslána",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val detail = buildString {
                    val preview = info.text.take(50)
                    append("\"$preview\"")
                    if (info.text.length > 50) append("...")
                    if (info.isAutoRetrying && info.nextRetryInSeconds != null) {
                        append(" — Další pokus za ${info.nextRetryInSeconds}s")
                    } else if (!info.isRetryable) {
                        append(" — ${info.errorMessage ?: "Chyba serveru"}")
                    } else if (info.attemptCount >= 4) {
                        append(" — Automatické pokusy vyčerpány")
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            TextButton(onClick = onRetry) { Text("Znovu") }
            TextButton(onClick = onCancel) { Text("Zrušit") }
        }
    }
}

/**
 * Banner shown when workspace clone failed or is in progress.
 */
@Composable
private fun WorkspaceBanner(
    info: MainViewModel.WorkspaceInfo,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCloning = info.status == "CLONING"
    val containerColor = if (isCloning) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isCloning) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isCloning) Icons.Default.Refresh else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isCloning) contentColor else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCloning) "Probíhá příprava prostředí..." else "Příprava prostředí selhala",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
                if (!isCloning && info.error != null) {
                    Text(
                        text = info.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            if (!isCloning) {
                TextButton(onClick = onRetry) { Text("Zkusit znovu") }
            }
        }
    }
}

/**
 * Banner shown when the Python orchestrator is unhealthy (circuit breaker OPEN).
 */
@Composable
private fun OrchestratorHealthBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Orchestrátor není dostupný. Tasky budou zpracovány po obnovení.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
