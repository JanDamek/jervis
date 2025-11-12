package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage

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
fun MainScreen(
    clients: List<ClientDto>,
    projects: List<ProjectDto>,
    selectedClientId: String?,
    selectedProjectId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    onClientSelected: (String) -> Unit,
    onProjectSelected: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onNavigate: (com.jervis.ui.navigation.Screen) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JERVIS Assistant") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Text("⋮", style = MaterialTheme.typography.headlineMedium)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // Configuration (mirrors Desktop: Settings window)
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.Settings)
                            }
                        )
                        HorizontalDivider()

                        // Tasks & Scheduling (mirrors Desktop: UserTasks/Scheduler windows)
                        DropdownMenuItem(
                            text = { Text("User Tasks") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.UserTasks)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Scheduler") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.Scheduler)
                            }
                        )
                        HorizontalDivider()

                        // Search & Logs (mirrors Desktop: RAGSearch/ErrorLogs windows)
                        DropdownMenuItem(
                            text = { Text("RAG Search") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.RagSearch)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Error Logs") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.ErrorLogs)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Indexing Status") },
                            onClick = {
                                showMenu = false
                                onNavigate(com.jervis.ui.navigation.Screen.IndexingStatus)
                            }
                        )

                        // TODO: Desktop Debug Console (WebSocket) - not yet implemented for mobile
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
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
                        .padding(16.dp),
            )

            Divider()

            // Chat area
            ChatArea(
                messages = chatMessages,
                isLoading = isLoading,
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
    onProjectSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Client selector
        var clientExpanded by remember { mutableStateOf(false) }

        Text(
            text = "Client",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = clientExpanded,
            onExpandedChange = { clientExpanded = it },
        ) {
            OutlinedTextField(
                value = clients.find { it.id == selectedClientId }?.name ?: "Select client...",
                onValueChange = {},
                readOnly = true,
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

        Spacer(modifier = Modifier.height(16.dp))

        // Project selector
        var projectExpanded by remember { mutableStateOf(false) }

        Text(
            text = "Project",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = projectExpanded,
            onExpandedChange = { projectExpanded = it },
        ) {
            OutlinedTextField(
                value = projects.find { it.id == selectedProjectId }?.name ?: "Select project...",
                onValueChange = {},
                readOnly = true,
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
    isLoading: Boolean,
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

                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Assistant is thinking...")
                        }
                    }
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement =
            if (message.from == ChatMessage.Sender.Me) {
                Arrangement.End
            } else {
                Arrangement.Start
            },
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        when (message.from) {
                            ChatMessage.Sender.Me -> MaterialTheme.colorScheme.primaryContainer
                            ChatMessage.Sender.Assistant -> MaterialTheme.colorScheme.secondaryContainer
                        },
                ),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    text =
                        when (message.from) {
                            ChatMessage.Sender.Me -> "You"
                            ChatMessage.Sender.Assistant -> "JERVIS"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            placeholder = { Text("Type your message...") },
            enabled = enabled,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 120.dp),
            maxLines = 4,
        )

        Button(
            onClick = onSendClick,
            enabled = enabled && inputText.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) {
            Text("Send")
        }
    }
}
