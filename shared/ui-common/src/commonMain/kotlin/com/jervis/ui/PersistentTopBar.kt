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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Stop
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
import com.jervis.dto.client.ClientDto
import com.jervis.dto.project.ProjectDto
import com.jervis.dto.project.ProjectGroupDto
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
    FINANCE(Icons.Default.AccountBalance, "Finance", 0),
    // Group 1: Management
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta úloh", 1),
    SCHEDULER(Icons.Default.CalendarMonth, "Kalendář", 1),
    INDEXING_QUEUE(Icons.Filled.Schedule, "Fronta indexace", 1),
    ENVIRONMENT_MANAGER(Icons.Default.Dns, "Správa prostředí", 1),
    // Group 2: Debug
    ERROR_LOGS(Icons.Default.BugReport, "Chybové logy", 2),
    RAG_SEARCH(Icons.Default.Search, "RAG Hledání", 2),
    // Group 3: Config
    SETTINGS(Icons.Default.Settings, "Nastavení", 3),
}

private fun TopBarMenuItem.toScreen(): Screen = when (this) {
    TopBarMenuItem.USER_TASKS -> Screen.UserTasks()
    TopBarMenuItem.MEETINGS -> Screen.Meetings
    TopBarMenuItem.FINANCE -> Screen.Finance
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
    connectionState: ConnectionViewModel.State,
    connectionStatusDetail: String? = null,
    onReconnect: () -> Unit,
    // Recording
    isRecording: Boolean,
    recordingDuration: Long,
    onNavigateToMeetings: () -> Unit,
    onQuickRecord: () -> Unit,
    onStopRecording: () -> Unit,
    // Paměťový graf panel toggle
    hasMemoryGraph: Boolean = false,
    onToggleThinkingGraphPanel: () -> Unit = {},
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

            // Quick record / stop button
            if (isRecording) {
                RecordingIndicator(
                    durationSeconds = recordingDuration,
                    onClick = onNavigateToMeetings,
                )
                JIconButton(
                    onClick = onStopRecording,
                    icon = Icons.Default.Stop,
                    contentDescription = "Zastavit nahrávání",
                    tint = Color.Red,
                )
            } else {
                JIconButton(
                    onClick = onQuickRecord,
                    icon = Icons.Default.Mic,
                    contentDescription = "Rychlé nahrávání",
                )
            }

            // Paměťový graf toggle
            if (hasMemoryGraph) {
                JIconButton(
                    onClick = onToggleThinkingGraphPanel,
                    icon = Icons.Default.AccountTree,
                    contentDescription = "Paměťový graf",
                )
            }

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
                statusDetail = connectionStatusDetail,
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
 * that opens a dropdown with search and sorted items (selected first).
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
    var searchQuery by remember { mutableStateOf("") }

    // Reset search when dropdown closes
    if (!expanded && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val clientName = clients.find { it.id == selectedClientId }?.name
    val projectName = when {
        selectedProjectId != null -> projects.find { it.id == selectedProjectId }?.name
        selectedGroupId != null -> projectGroups.find { it.id == selectedGroupId }?.name
        else -> null
    }

    val displayText = when {
        selectedClientId == "__global__" && projectName != null -> "Global / $projectName"
        selectedClientId == "__global__" -> "Global"
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
            // Search field
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Hledat...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            val query = searchQuery.trim().lowercase()

            // --- Clients section ---
            // Sort: selected first, then alphabetical. Filter by search.
            val sortedClients = clients
                .filter { query.isEmpty() || it.name.lowercase().contains(query) }
                .sortedWith(compareByDescending<ClientDto> { it.id == selectedClientId }.thenBy { it.name })

            if (query.isEmpty() || "global".contains(query)) {
                Text(
                    text = "Klient",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Global",
                            fontWeight = if (selectedClientId == "__global__") {
                                androidx.compose.ui.text.font.FontWeight.Bold
                            } else null,
                        )
                    },
                    onClick = {
                        onClientSelected("__global__")
                    },
                )
            } else if (sortedClients.isNotEmpty()) {
                Text(
                    text = "Klient",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            sortedClients.forEach { client ->
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
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // --- Projects / Groups section ---
            val ungroupedProjects = projects
                .filter { it.groupId == null }
                .filter { query.isEmpty() || it.name.lowercase().contains(query) }
                .sortedWith(compareByDescending<ProjectDto> { it.id == selectedProjectId }.thenBy { it.name })

            val filteredGroups = projectGroups
                .filter { group ->
                    if (query.isEmpty()) return@filter true
                    val groupMatch = group.name.lowercase().contains(query)
                    val childMatch = projects.any { it.groupId == group.id && it.name.lowercase().contains(query) }
                    groupMatch || childMatch
                }
                .sortedWith(compareByDescending<ProjectGroupDto> { it.id == selectedGroupId }.thenBy { it.name })

            if (ungroupedProjects.isNotEmpty() || filteredGroups.isNotEmpty() || query.isEmpty()) {
                Text(
                    text = "Projekt / Skupina",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // "(Vše)" option
            if (query.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "(Vše)",
                            fontWeight = if (selectedProjectId == null && selectedGroupId == null) {
                                androidx.compose.ui.text.font.FontWeight.Bold
                            } else null,
                        )
                    },
                    onClick = {
                        onProjectSelected(null)
                        expanded = false
                    },
                )
            }

            // Ungrouped projects
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
            if (ungroupedProjects.isNotEmpty() && filteredGroups.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // Project groups with their projects
            filteredGroups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = group.name,
                            fontWeight = if (group.id == selectedGroupId) {
                                androidx.compose.ui.text.font.FontWeight.Bold
                            } else null,
                        )
                    },
                    onClick = {
                        onGroupSelected(group.id)
                        expanded = false
                    },
                )
                // Show projects within this group (indented), filtered by search
                val groupProjects = projects
                    .filter { it.groupId == group.id }
                    .filter { query.isEmpty() || it.name.lowercase().contains(query) }
                    .sortedWith(compareByDescending<ProjectDto> { it.id == selectedProjectId }.thenBy { it.name })
                groupProjects.forEach { project ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "    ${project.name}",
                                fontWeight = if (project.id == selectedProjectId) {
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                } else null,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = {
                            onProjectSelected(project.id)
                            expanded = false
                        },
                    )
                }
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
 * Connection status indicator — colored dot when connected,
 * "Offline" chip when disconnected, spinner when connecting.
 */
@Composable
private fun ConnectionIndicator(
    connectionState: ConnectionViewModel.State,
    statusDetail: String? = null,
    onReconnect: () -> Unit,
) {
    val semanticColors = LocalJervisSemanticColors.current

    when (connectionState) {
        ConnectionViewModel.State.CONNECTED -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(semanticColors.success, CircleShape),
                )
                if (!statusDetail.isNullOrBlank()) {
                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 7.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        ConnectionViewModel.State.DISCONNECTED -> {
            // "Offline" chip — clickable for manual reconnect
            Surface(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onReconnect),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (!statusDetail.isNullOrBlank()) {
                        Text(
                            text = statusDetail,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 7.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
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
                    text = statusDetail ?: "connecting",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = semanticColors.warning,
                    maxLines = 1,
                )
            }
        }
    }
}
