package com.jervis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGroupDto
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.LocalJervisSemanticColors
import com.jervis.ui.navigation.Screen

/**
 * Main menu items — reorganized: daily first, utility second, settings last.
 */
private enum class TopBarMenuItem(val icon: ImageVector, val title: String, val group: Int) {
    // Group 0: Daily
    USER_TASKS(Icons.AutoMirrored.Filled.List, "Uživatelské úlohy", 0),
    MEETINGS(Icons.Default.Mic, "Meetingy", 0),
    // Group 1: Management
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta úloh", 1),
    SCHEDULER(Icons.Default.CalendarMonth, "Plánovač", 1),
    INDEXING_QUEUE(Icons.Filled.Schedule, "Fronta indexace", 1),
    ENVIRONMENT_MANAGER(Icons.Default.Dns, "Správa prostředí", 1),
    // Group 2: Debug
    ERROR_LOGS(Icons.Default.BugReport, "Chybové logy", 2),
    RAG_SEARCH(Icons.Default.Search, "RAG Hledání", 2),
    // Group 3: Config
    SETTINGS(Icons.Default.Settings, "Nastavení", 3),
}

private fun TopBarMenuItem.toScreen(): Screen = when (this) {
    TopBarMenuItem.USER_TASKS -> Screen.UserTasks
    TopBarMenuItem.MEETINGS -> Screen.Meetings
    TopBarMenuItem.PENDING_TASKS -> Screen.PendingTasks
    TopBarMenuItem.SCHEDULER -> Screen.Scheduler
    TopBarMenuItem.INDEXING_QUEUE -> Screen.IndexingQueue
    TopBarMenuItem.ENVIRONMENT_MANAGER -> Screen.EnvironmentManager()
    TopBarMenuItem.ERROR_LOGS -> Screen.ErrorLogs
    TopBarMenuItem.RAG_SEARCH -> Screen.RagSearch
    TopBarMenuItem.SETTINGS -> Screen.Settings
}

/**
 * Persistent top bar — always visible above all screens.
 * Contains: back arrow, menu, client/project selector, recording indicator,
 * agent status, K8s badge, connection status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersistentTopBar(
    // Navigation
    canGoBack: Boolean,
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit,
    // Client/Project
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<ProjectGroupDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    selectedGroupId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onGroupSelected: (String) -> Unit,
    // Connection
    connectionState: MainViewModel.ConnectionState,
    onReconnect: () -> Unit,
    // Recording
    isRecording: Boolean,
    recordingDuration: Long,
    onNavigateToMeetings: () -> Unit,
    // Agent status
    isAgentRunning: Boolean,
    runningTaskType: String?,
    queueSize: Int,
    onAgentStatusClick: () -> Unit,
    // Environment
    hasEnvironment: Boolean,
    onToggleEnvironmentPanel: () -> Unit,
    // User task badge
    userTaskCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back arrow
            if (canGoBack) {
                JIconButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zpět",
                )
            }

            // Menu
            MenuDropdown(onNavigate = onNavigate, userTaskCount = userTaskCount)

            // Client / Project compact selector — takes remaining space
            ClientProjectCompactSelector(
                clients = clients,
                projects = projects,
                projectGroups = projectGroups,
                selectedClientId = selectedClientId,
                selectedProjectId = selectedProjectId,
                selectedGroupId = selectedGroupId,
                onClientSelected = onClientSelected,
                onProjectSelected = onProjectSelected,
                onGroupSelected = onGroupSelected,
                modifier = Modifier.weight(1f),
            )

            // Recording indicator
            if (isRecording) {
                RecordingIndicator(
                    durationSeconds = recordingDuration,
                    onClick = onNavigateToMeetings,
                )
            }

            // Agent status icon
            AgentStatusIcon(
                isRunning = isAgentRunning,
                taskType = runningTaskType,
                queueSize = queueSize,
                onClick = onAgentStatusClick,
            )

            // K8s environment badge
            if (hasEnvironment) {
                JIconButton(
                    onClick = onToggleEnvironmentPanel,
                    icon = Icons.Default.Dns,
                    contentDescription = "Prostředí",
                )
            }

            // Connection status
            ConnectionIndicator(
                connectionState = connectionState,
                onReconnect = onReconnect,
            )
        }
    }
}

@Composable
private fun MenuDropdown(
    onNavigate: (Screen) -> Unit,
    userTaskCount: Int = 0,
) {
    Box {
        var menuExpanded by remember { mutableStateOf(false) }

        BadgedBox(
            badge = {
                if (userTaskCount > 0) {
                    Badge { Text("$userTaskCount") }
                }
            },
        ) {
            JIconButton(
                onClick = { menuExpanded = true },
                icon = Icons.Default.Menu,
                contentDescription = "Menu",
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            var lastGroup = -1
            TopBarMenuItem.entries.forEach { item ->
                if (item.group != lastGroup && lastGroup >= 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                lastGroup = item.group

                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(item.title)
                            if (item == TopBarMenuItem.USER_TASKS && userTaskCount > 0) {
                                Badge { Text("$userTaskCount") }
                            }
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

/**
 * Compact client/project selector — shows "ClientName / ProjectName" as clickable text
 * that opens a dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientProjectCompactSelector(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    projectGroups: List<ProjectGroupDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    selectedGroupId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onGroupSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val clientName = clients.find { it.id == selectedClientId }?.name
    val projectName = when {
        selectedProjectId != null -> projects.find { it.id == selectedProjectId }?.name
        selectedGroupId != null -> projectGroups.find { it.id == selectedGroupId }?.name
        else -> null
    }

    val displayText = when {
        clientName != null && projectName != null -> "$clientName / $projectName"
        clientName != null -> "$clientName / (Vše)"
        else -> "Vyberte klienta..."
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        // Compact clickable text instead of OutlinedTextField
        Row(
            modifier = Modifier
                .menuAnchor()
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Clients section
            Text(
                text = "Klient",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            clients.forEach { client ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = client.name,
                            fontWeight = if (client.id == selectedClientId) {
                                androidx.compose.ui.text.font.FontWeight.Bold
                            } else null,
                        )
                    },
                    onClick = {
                        onClientSelected(client.id)
                        // Don't close — let user pick project too
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Projects section
            Text(
                text = "Projekt / Skupina",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Ungrouped projects
            val ungroupedProjects = projects.filter { it.groupId == null }
            ungroupedProjects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = project.name,
                            fontWeight = if (project.id == selectedProjectId) {
                                androidx.compose.ui.text.font.FontWeight.Bold
                            } else null,
                        )
                    },
                    onClick = {
                        onProjectSelected(project.id)
                        expanded = false
                    },
                )
            }

            // Separator if both exist
            if (ungroupedProjects.isNotEmpty() && projectGroups.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // Project groups
            projectGroups.forEach { group ->
                DropdownMenuItem(
                    text = { Text("${group.name}") },
                    onClick = {
                        onGroupSelected(group.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Small recording indicator — red blinking dot + duration.
 */
@Composable
private fun RecordingIndicator(
    durationSeconds: Long,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recAlpha",
    )

    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                .background(Color.Red, CircleShape),
        )
        Text(
            text = formatDuration(durationSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Red,
            fontSize = 11.sp,
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}

/**
 * Small agent status icon — spinner when running, dot when idle.
 */
@Composable
private fun AgentStatusIcon(
    isRunning: Boolean,
    taskType: String?,
    queueSize: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "\u25CF",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text(
                text = if (isRunning) (taskType ?: "agent") else "idle",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (queueSize > 0) {
                Text(
                    text = "+$queueSize",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Connection status indicator — colored dot + tiny label.
 */
@Composable
private fun ConnectionIndicator(
    connectionState: MainViewModel.ConnectionState,
    onReconnect: () -> Unit,
) {
    val semanticColors = LocalJervisSemanticColors.current

    when (connectionState) {
        MainViewModel.ConnectionState.CONNECTED -> {
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(8.dp)
                    .background(semanticColors.success, CircleShape),
            )
        }
        MainViewModel.ConnectionState.DISCONNECTED -> {
            JIconButton(
                onClick = onReconnect,
                icon = Icons.Default.Refresh,
                contentDescription = "Reconnect",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        else -> {
            // CONNECTING / RECONNECTING
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = semanticColors.warning,
                )
                Text(
                    text = "connecting",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = semanticColors.warning,
                )
            }
        }
    }
}
