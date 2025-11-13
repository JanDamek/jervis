package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.desktop.ConnectionManager
import com.jervis.dto.events.DebugEventDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Debug session data model
 */
data class DebugSession(
    val id: String,
    val promptType: String,
    val systemPrompt: String,
    val userPrompt: String,
    val startTime: LocalDateTime,
    val clientId: String? = null,
    val clientName: String? = null,
    var responseBuffer: String = "",
    var completionTime: LocalDateTime? = null,
) {
    fun complete() {
        completionTime = LocalDateTime.now()
    }

    fun isCompleted(): Boolean = completionTime != null

    fun getTabLabel(): String = buildString {
        if (clientName != null) {
            append("[$clientName] ")
        } else {
            append("[System] ")
        }
        append(promptType)
        if (isCompleted()) {
            append(" ✓")
        }
    }
}

/**
 * Debug window with tab-based sessions
 */
@Composable
fun DebugWindow(connectionManager: ConnectionManager) {
    val sessions = remember { mutableStateMapOf<String, DebugSession>() }
    var selectedTabIndex by remember { mutableStateOf(0) }

    // Process debug events from WebSocket flow
    // Use LaunchedEffect to collect events only once
    LaunchedEffect(connectionManager) {
        connectionManager.debugWebSocketFlow?.collect { event ->
            when (event) {
                is DebugEventDto.SessionStarted -> {
                    if (!sessions.containsKey(event.sessionId)) {
                        val newSession = DebugSession(
                            id = event.sessionId,
                            promptType = event.promptType,
                            systemPrompt = event.systemPrompt,
                            userPrompt = event.userPrompt,
                            startTime = LocalDateTime.now(),
                            clientId = event.clientId,
                            clientName = event.clientName
                        )
                        sessions[event.sessionId] = newSession
                        // Don't automatically change tab index - let user stay on current tab
                    }
                }
                is DebugEventDto.ResponseChunkDto -> {
                    sessions[event.sessionId]?.let { session ->
                        sessions[event.sessionId] = session.copy(
                            responseBuffer = session.responseBuffer + event.chunk
                        )
                    }
                }
                is DebugEventDto.SessionCompletedDto -> {
                    sessions[event.sessionId]?.complete()
                }
            }
        }
    }

    // Convert sessions map to list directly - avoid derivedStateOf which can cause race conditions
    val currentSessions = sessions.values.toList()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (currentSessions.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "No LLM Sessions Yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "LLM calls will appear here as tabs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Safely clamp index to valid range
                val safeIndex = if (selectedTabIndex >= currentSessions.size) {
                    currentSessions.size - 1
                } else if (selectedTabIndex < 0) {
                    0
                } else {
                    selectedTabIndex
                }

                // Update selectedTabIndex if it was clamped
                if (safeIndex != selectedTabIndex) {
                    selectedTabIndex = safeIndex
                }

                // Tab bar with close buttons
                Row(modifier = Modifier.fillMaxWidth()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = safeIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        currentSessions.forEachIndexed { index, session ->
                            Tab(
                                selected = safeIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Text(session.getTabLabel())
                                        IconButton(
                                            onClick = {
                                                sessions.remove(session.id)
                                                // Adjust selected index if needed
                                                if (selectedTabIndex >= sessions.size && selectedTabIndex > 0) {
                                                    selectedTabIndex = sessions.size - 1
                                                }
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Close tab",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Close All Completed button
                    if (currentSessions.any { it.isCompleted() }) {
                        Button(
                            onClick = {
                                val completedIds = sessions.values.filter { it.isCompleted() }.map { it.id }
                                completedIds.forEach { sessions.remove(it) }
                                // Reset index if current tab was closed
                                if (selectedTabIndex >= sessions.size && sessions.isNotEmpty()) {
                                    selectedTabIndex = sessions.size - 1
                                } else if (sessions.isEmpty()) {
                                    selectedTabIndex = 0
                                }
                            },
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text("Close All Completed")
                        }
                    }
                }

                HorizontalDivider()

                // Tab content
                val selectedSession = currentSessions.getOrNull(safeIndex)
                if (selectedSession != null) {
                    SessionTabContent(selectedSession)
                }
            }
        }
    }
}

/**
 * Content for a single session tab
 */
@Composable
fun SessionTabContent(session: DebugSession) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val clipboard = LocalClipboardManager.current

    Row(modifier = Modifier.fillMaxSize()) {
        // Left side - Prompts
        Column(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Session info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Session Info",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    SessionInfoRow("ID", session.id.take(8))
                    SessionInfoRow("Type", session.promptType)
                    SessionInfoRow("Client", session.clientName ?: "System")
                    SessionInfoRow("Started", session.startTime.format(formatter))
                    if (session.isCompleted()) {
                        SessionInfoRow("Status", "COMPLETED ✓")
                    } else {
                        SessionInfoRow("Status", "STREAMING...")
                    }
                }
            }

            // System Prompt
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "System Prompt",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(session.systemPrompt)) }) {
                            Text("Copy")
                        }
                    }
                    SelectionContainer {
                        Text(
                            session.systemPrompt,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // User Prompt
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "User Prompt",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(session.userPrompt)) }) {
                            Text("Copy")
                        }
                    }
                    SelectionContainer {
                        Text(
                            session.userPrompt,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        VerticalDivider()

        // Right side - Response
        Card(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Streaming Response",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(session.responseBuffer)) },
                        enabled = session.responseBuffer.isNotEmpty()
                    ) {
                        Text("Copy")
                    }
                    if (!session.isCompleted()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (session.responseBuffer.isEmpty()) {
                        Text(
                            "Waiting for response...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                session.responseBuffer,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
