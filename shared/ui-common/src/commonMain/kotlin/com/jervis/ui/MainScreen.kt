package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.design.JIconButton
import com.jervis.ui.navigation.Screen

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
    selectedClientId: String?,
    selectedProjectId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    queueSize: Int = 0,
    runningProjectId: String? = null,
    runningProjectName: String? = null,
    runningTaskPreview: String? = null,
    runningTaskType: String? = null,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onNavigate: (Screen) -> Unit = {},
    onAgentStatusClick: () -> Unit = {},
    connectionState: MainViewModel.ConnectionState = MainViewModel.ConnectionState.CONNECTED,
    onReconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        ChatContent(
            clients = clients,
            projects = projects,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            chatMessages = chatMessages,
            inputText = inputText,
            isLoading = isLoading,
            queueSize = queueSize,
            runningProjectId = runningProjectId,
            runningProjectName = runningProjectName,
            runningTaskPreview = runningTaskPreview,
            runningTaskType = runningTaskType,
            onClientSelected = onClientSelected,
            onProjectSelected = onProjectSelected,
            onInputChanged = onInputChanged,
            onSendClick = onSendClick,
            onAgentStatusClick = onAgentStatusClick,
            onNavigate = onNavigate,
            connectionState = connectionState,
            onReconnect = onReconnect,
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
    selectedClientId: String?,
    selectedProjectId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    queueSize: Int,
    runningProjectId: String?,
    runningProjectName: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onAgentStatusClick: () -> Unit,
    onNavigate: (Screen) -> Unit,
    connectionState: MainViewModel.ConnectionState = MainViewModel.ConnectionState.CONNECTED,
    onReconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Client and Project Selectors + Menu
        SelectorsRow(
            clients = clients,
            projects = projects,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            onClientSelected = onClientSelected,
            onProjectSelected = onProjectSelected,
            onNavigate = onNavigate,
            connectionState = connectionState,
            onReconnect = onReconnect,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // Chat area
        ChatArea(
            messages = chatMessages,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorsRow(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
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

        // Project selector
        var projectExpanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = projects.find { it.id == selectedProjectId }?.name ?: "Vyberte projekt...",
                onValueChange = {},
                readOnly = true,
                label = { Text("Projekt") },
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
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            onProjectSelected(project.id)
                            projectExpanded = false
                        },
                    )
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
