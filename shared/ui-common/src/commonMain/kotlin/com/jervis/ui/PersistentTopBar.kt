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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.automirrored.filled.Chat
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
 * Main menu items — minimal set as top bar icons.
 * Meetings, Calendar, Settings only. Everything else is sidebars or inline.
 */
private enum class TopBarMenuItem(val icon: ImageVector, val title: String) {
    MEETINGS(Icons.Default.Mic, "Meetingy"),
    CALENDAR(Icons.Default.CalendarMonth, "Kalendář"),
    DASHBOARD(Icons.Outlined.Insights, "Dashboard"),
    SETTINGS(Icons.Default.Settings, "Nastavení"),
}

private fun TopBarMenuItem.toScreen(): Screen = when (this) {
    TopBarMenuItem.MEETINGS -> Screen.Meetings
    TopBarMenuItem.CALENDAR -> Screen.Calendar
    TopBarMenuItem.DASHBOARD -> Screen.Dashboard
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
    // Assistant (companion live hints during meeting)
    assistantActive: Boolean = false,
    isOnAssistant: Boolean = false,
    onOpenAssistant: () -> Unit = {},
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

            // Navigation icons — Meetings, Calendar, Settings
            TopBarMenuItem.entries.forEach { item ->
                JIconButton(
                    onClick = { onNavigate(item.toScreen()) },
                    icon = item.icon,
                    contentDescription = item.title,
                )
            }

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

            // Assistant ↔ Chat toggle — Assistant is the home page during an
            // active meeting; click switches back to chat and vice versa.
            if (assistantActive) {
                val icon = if (isOnAssistant) Icons.AutoMirrored.Filled.Chat else Icons.Default.Headphones
                val desc = if (isOnAssistant) "Přepnout na chat" else "Přepnout na asistenta (běží)"
                JIconButton(
                    onClick = onOpenAssistant,
                    icon = icon,
                    contentDescription = desc,
                    tint = Color(0xFF2E7D32),
                )
            }

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
            // Anchor ("Mazluš...") is narrow; without explicit width the popup
            // inherits ~60 dp and renders Hledat/client names character-per-line.
            // Force a usable minimum so the dropdown is readable on mobile.
            modifier = Modifier.widthIn(min = 280.dp),
        ) {
            // Search field
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Hledat...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier
                    .widthIn(min = 256.dp)
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
