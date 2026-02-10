package com.jervis.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.navigation.Screen

/**
 * Main menu items for the dropdown menu.
 * Each item navigates to a separate screen.
 */
private enum class MainMenuItem(val icon: ImageVector, val title: String) {
    SETTINGS(Icons.Default.Settings, "Nastaven\u00ED"),
    USER_TASKS(Icons.AutoMirrored.Filled.List, "U\u017Eivatelsk\u00E9 \u00FAlohy"),
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta \u00FAloh"),
    SCHEDULER(Icons.Default.CalendarMonth, "Pl\u00E1nova\u010D"),
    MEETINGS(Icons.Default.Mic, "Meetingy"),
    RAG_SEARCH(Icons.Default.Search, "RAG Hled\u00E1n\u00ED"),
    ERROR_LOGS(Icons.Default.BugReport, "Chybov\u00E9 logy"),
}

private fun MainMenuItem.toScreen(): Screen = when (this) {
    MainMenuItem.SETTINGS -> Screen.Settings
    MainMenuItem.USER_TASKS -> Screen.UserTasks
    MainMenuItem.PENDING_TASKS -> Screen.PendingTasks
    MainMenuItem.SCHEDULER -> Screen.Scheduler
    MainMenuItem.MEETINGS -> Screen.Meetings
    MainMenuItem.RAG_SEARCH -> Screen.RagSearch
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
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

@Composable
private fun ChatArea(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = modifier) {
        if (messages.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Zat\u00EDm \u017E\u00E1dn\u00E9 zpr\u00E1vy. Za\u010Dn\u011Bte konverzaci!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages.size) { index ->
                    ChatMessageItem(messages[index])
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isMe = message.from == ChatMessage.Sender.Me

    // Progress messages are displayed differently (compact, with spinner)
    if (message.messageType == ChatMessage.MessageType.PROGRESS) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // standard chat bubble
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement =
                if (isMe) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                },
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (isMe) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                    ),
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text =
                            if (isMe) {
                                "J\u00E1"
                            } else {
                                "Asistent"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Selectable message text
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    // Show timestamp if available
                    message.timestamp?.let { ts ->
                        if (ts.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ts,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clickable agent status row between chat and input.
 * Shows current agent state (idle/running) with queue count.
 * Click navigates to workload detail screen.
 */
@Composable
private fun AgentStatusRow(
    runningProjectId: String?,
    runningProjectName: String?,
    runningTaskPreview: String?,
    runningTaskType: String?,
    queueSize: Int,
    onClick: () -> Unit,
) {
    val isRunning = runningProjectId != null && runningProjectId != "none"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isRunning) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                },
            )
            .padding(horizontal = 16.dp)
            .heightIn(min = JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status indicator
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "\u25CF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Status text
        Column(modifier = Modifier.weight(1f)) {
            if (isRunning) {
                Text(
                    text = buildString {
                        if (runningTaskType != null && runningTaskType.isNotBlank()) {
                            append("$runningTaskType: ")
                        }
                        append(runningProjectName ?: runningProjectId)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (runningTaskPreview != null && runningTaskPreview.isNotBlank()) {
                    Text(
                        text = runningTaskPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Agent: Ne\u010Dinn\u00FD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Queue badge
        if (queueSize > 0) {
            Text(
                text = "Fronta: $queueSize",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Chevron
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = "Detail",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun InputArea(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean,
    queueSize: Int = 0,
    runningProjectId: String? = null,
    currentProjectId: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        JTextField(
            value = inputText,
            onValueChange = onInputChanged,
            label = "",
            placeholder = "Napi\u0161te zpr\u00E1vu...",
            enabled = enabled,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 120.dp),
            maxLines = 4,
            singleLine = false,
        )

        val buttonText =
            when {
                runningProjectId == null || runningProjectId == "none" -> "Odeslat"
                runningProjectId == currentProjectId -> "Odeslat" // Inline delivery to running task
                else -> "Do fronty" // Different project or queue has items
            }

        JPrimaryButton(
            onClick = onSendClick,
            enabled = enabled && inputText.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) {
            Text(buttonText)
        }
    }
}
