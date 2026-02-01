package com.jervis.ui

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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.design.JTopBar
import com.jervis.ui.util.rememberClipboardManager

/**
 * Main screen for Jervis Mobile - inspired by Desktop MainWindow
 *
 * Layout:
 * - Top: Client Selector + Project Selector
 * - Middle: Chat messages (scrollable)
 * - Bottom: Input field + Send button
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
    connectionState: String = "DISCONNECTED", // CONNECTED, CONNECTING, RECONNECTING, DISCONNECTED
    queueSize: Int = 0,
    runningProjectId: String? = null,
    runningProjectName: String? = null,
    runningTaskPreview: String? = null,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onNavigate: (com.jervis.ui.navigation.Screen) -> Unit = {},
    onReconnectClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    // Obrazovka "Spojení ztraceno" s možností manuálního obnovení
    if (connectionState == "DISCONNECTED" && onReconnectClick != null) {
        // Zde by mohl být speciální UI pro odpojený stav, 
        // ale my už máme overlay v App.kt. 
        // Přidáme alespoň tlačítko pro refresh do TopBaru, pokud je potřeba.
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "JERVIS Assistant",
                actions = {
                    // Connection status indicator
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            text = when (connectionState) {
                                "CONNECTED" -> "●"
                                "CONNECTING" -> "⟳"
                                "RECONNECTING" -> "⟳"
                                else -> "○"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = when (connectionState) {
                                "CONNECTED" -> MaterialTheme.colorScheme.primary
                                "CONNECTING", "RECONNECTING" -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Text("⋮", style = MaterialTheme.typography.headlineMedium)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        // Configuration (mirrors Desktop: Settings window)
                        DropdownMenuItem(
                            text = { Text("Nastavení") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.Settings)
                            },
                        )
                        // Connections menu removed – connection management is handled inside Settings → Client/Project edit
                        HorizontalDivider()

                        // Tasks & Scheduling (mirrors Desktop: UserTasks/Scheduler windows)
                        DropdownMenuItem(
                            text = { Text("Uživatelské úlohy") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.UserTasks)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Fronta úloh") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.PendingTasks)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Plánovač") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.Scheduler)
                            },
                        )
                        HorizontalDivider()

                        // Search & Logs (mirrors Desktop: RAGSearch/ErrorLogs windows)
                        DropdownMenuItem(
                            text = { Text("RAG Hledání") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.RagSearch)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Chybové logy") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.ErrorLogs)
                            },
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Client and Project Selectors
            SelectorsRow(
                clients = clients,
                projects = projects,
                selectedClientId = selectedClientId,
                selectedProjectId = selectedProjectId,
                onClientSelected = onClientSelected,
                onProjectSelected = onProjectSelected,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Divider()

            // Chat area
            ChatArea(
                messages = chatMessages,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            )

            Divider()

            // Input area
            InputArea(
                inputText = inputText,
                onInputChanged = onInputChanged,
                onSendClick = onSendClick,
                enabled = selectedClientId != null && selectedProjectId != null && !isLoading,
                queueSize = queueSize,
                runningProjectId = runningProjectId,
                runningProjectName = runningProjectName,
                runningTaskPreview = runningTaskPreview,
                currentProjectId = selectedProjectId,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            )
        }
    }
}

@Composable
private fun SelectorsRow(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Client selector
        var clientExpanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = clientExpanded,
            onExpandedChange = { clientExpanded = it },
            modifier = Modifier.weight(1f)
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
            modifier = Modifier.weight(1f)
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
                    text = "No messages yet. Start a conversation!",
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
    val clipboard = rememberClipboardManager()
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
                            if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text =
                            if (isMe) "Já"
                            else "Asistent",
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

@Composable
private fun InputArea(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean,
    queueSize: Int = 0,
    runningProjectId: String? = null,
    runningProjectName: String? = null,
    runningTaskPreview: String? = null,
    currentProjectId: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Show queue info if something is running
        if (runningProjectId != null && runningProjectId != "none") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Project name + task preview on single line
                Text(
                    text = buildString {
                        append("⚙️ ${runningProjectName ?: runningProjectId}")
                        if (runningTaskPreview != null) {
                            append(": $runningTaskPreview")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (queueSize > 0) {
                    Text(
                        text = "Fronta: $queueSize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChanged,
                placeholder = { Text("Napište zprávu...") },
                enabled = enabled,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 120.dp),
                maxLines = 4,
            )

            val buttonText = when {
                runningProjectId == null || runningProjectId == "none" -> "Odeslat"
                runningProjectId != currentProjectId -> "Do fronty"
                queueSize > 0 -> "Do fronty (${queueSize + 1})"
                else -> "Do fronty"
            }

            Button(
                onClick = onSendClick,
                enabled = enabled && inputText.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text(buttonText)
            }
        }
    }
}
