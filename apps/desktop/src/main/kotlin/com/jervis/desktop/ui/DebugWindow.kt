package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jervis.desktop.ConnectionManager
import com.jervis.dto.events.DebugEventDto
import com.jervis.ui.util.rememberClipboardManager
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
    val correlationId: String? = null, // For grouping by correlationId
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
 * Correlation group containing all LLM sessions with the same correlationId
 */
data class CorrelationGroup(
    val correlationId: String,
    val sessions: MutableList<DebugSession> = mutableListOf()
) {
    fun getGroupLabel(): String {
        val firstSession = sessions.firstOrNull()
        val clientName = firstSession?.clientName ?: "System"
        val sessionCount = sessions.size
        return "[$clientName] ${correlationId.take(8)} ($sessionCount LLM calls)"
    }

    fun getLatestSession(): DebugSession? = sessions.firstOrNull()
}

/**
 * Debug window with correlationId-based grouping
 */
@Composable
fun DebugWindow(connectionManager: ConnectionManager) {
    val correlationGroups = remember { mutableStateMapOf<String, CorrelationGroup>() }
    var selectedGroupIndex by remember { mutableStateOf(0) }
    var selectedSessionInGroup by remember { mutableStateOf<DebugSession?>(null) }

    // Process debug events from WebSocket flow
    LaunchedEffect(connectionManager) {
        connectionManager.debugWebSocketFlow?.collect { event ->
            when (event) {
                is DebugEventDto.SessionStarted -> {
                    val correlationId = event.correlationId ?: event.sessionId // Fallback to sessionId if no correlationId

                    val newSession = DebugSession(
                        id = event.sessionId,
                        promptType = event.promptType,
                        systemPrompt = event.systemPrompt,
                        userPrompt = event.userPrompt,
                        startTime = LocalDateTime.now(),
                        clientId = event.clientId,
                        clientName = event.clientName,
                        correlationId = correlationId
                    )

                    // Get or create correlation group
                    val group = correlationGroups.getOrPut(correlationId) {
                        CorrelationGroup(correlationId)
                    }

                    // Add session to the beginning of the list (newest first)
                    group.sessions.add(0, newSession)

                    // Trigger recomposition
                    correlationGroups[correlationId] = group
                }
                is DebugEventDto.ResponseChunkDto -> {
                    // Find the session in any group and update it
                    correlationGroups.values.forEach { group ->
                        val sessionIndex = group.sessions.indexOfFirst { it.id == event.sessionId }
                        if (sessionIndex >= 0) {
                            val session = group.sessions[sessionIndex]
                            group.sessions[sessionIndex] = session.copy(
                                responseBuffer = session.responseBuffer + event.chunk
                            )
                            // Trigger recomposition
                            correlationGroups[group.correlationId] = group
                        }
                    }
                }
                is DebugEventDto.SessionCompletedDto -> {
                    // Find the session in any group and mark as complete
                    correlationGroups.values.forEach { group ->
                        group.sessions.find { it.id == event.sessionId }?.complete()
                    }
                }
                // Task stream events - ignored in debug window (these are for task monitoring)
                is DebugEventDto.TaskCreated,
                is DebugEventDto.TaskStateTransition,
                is DebugEventDto.QualificationStart,
                is DebugEventDto.QualificationDecision,
                is DebugEventDto.GpuTaskPickup,
                is DebugEventDto.PlanCreated,
                is DebugEventDto.PlanStatusChanged,
                is DebugEventDto.PlanStepAdded,
                is DebugEventDto.StepExecutionStart,
                is DebugEventDto.StepExecutionCompleted,
                is DebugEventDto.FinalizerStart,
                is DebugEventDto.FinalizerComplete -> {
                    // These events are for task flow monitoring, not LLM session debugging
                    // They will be handled in a separate TaskMonitorWindow in the future
                }
            }
        }
    }

    // Convert groups to sorted list (newest first)
    val currentGroups = correlationGroups.values.sortedByDescending {
        it.getLatestSession()?.startTime
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (currentGroups.isEmpty()) {
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
                // Safely clamp group index to valid range
                val safeGroupIndex = if (selectedGroupIndex >= currentGroups.size) {
                    currentGroups.size - 1
                } else if (selectedGroupIndex < 0) {
                    0
                } else {
                    selectedGroupIndex
                }

                // Update selectedGroupIndex if it was clamped
                if (safeGroupIndex != selectedGroupIndex) {
                    selectedGroupIndex = safeGroupIndex
                }

                // Group tabs
                Row(modifier = Modifier.fillMaxWidth()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = safeGroupIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        currentGroups.forEachIndexed { index, group ->
                            Tab(
                                selected = safeGroupIndex == index,
                                onClick = {
                                    selectedGroupIndex = index
                                    selectedSessionInGroup = group.getLatestSession()
                                },
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Text(group.getGroupLabel())
                                        IconButton(
                                            onClick = {
                                                correlationGroups.remove(group.correlationId)
                                                // Adjust selected index if needed
                                                if (selectedGroupIndex >= correlationGroups.size && selectedGroupIndex > 0) {
                                                    selectedGroupIndex = correlationGroups.size - 1
                                                }
                                                selectedSessionInGroup = null
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Close group",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Close All Completed Groups button
                    if (correlationGroups.values.any { group -> group.sessions.all { it.isCompleted() } }) {
                        Button(
                            onClick = {
                                val completedGroupIds = correlationGroups.values
                                    .filter { group -> group.sessions.all { it.isCompleted() } }
                                    .map { it.correlationId }
                                completedGroupIds.forEach { correlationGroups.remove(it) }
                                // Reset index if current tab was closed
                                if (selectedGroupIndex >= correlationGroups.size && correlationGroups.isNotEmpty()) {
                                    selectedGroupIndex = correlationGroups.size - 1
                                } else if (correlationGroups.isEmpty()) {
                                    selectedGroupIndex = 0
                                }
                                selectedSessionInGroup = null
                            },
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text("Close All Completed")
                        }
                    }
                }

                HorizontalDivider()

                // Group content with session list
                val selectedGroup = currentGroups.getOrNull(safeGroupIndex)
                if (selectedGroup != null) {
                    CorrelationGroupContent(
                        group = selectedGroup,
                        selectedSession = selectedSessionInGroup,
                        onSessionSelected = { selectedSessionInGroup = it }
                    )
                }
            }
        }
    }
}

/**
 * Content for a correlation group showing list of sessions and selected session detail
 */
@Composable
fun CorrelationGroupContent(
    group: CorrelationGroup,
    selectedSession: DebugSession?,
    onSessionSelected: (DebugSession) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left side - Session list (newest first)
        Column(
            modifier = Modifier
                .weight(0.3f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            Text(
                text = "LLM Sessions (${group.sessions.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp)
            )
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(group.sessions) { session ->
                    SessionListItem(
                        session = session,
                        isSelected = selectedSession?.id == session.id,
                        onClick = { onSessionSelected(session) }
                    )
                }
            }
        }

        VerticalDivider()

        // Right side - Session detail
        if (selectedSession != null) {
            SessionTabContent(selectedSession)
        } else {
            Box(
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select an LLM session from the list",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Single session list item
 */
@Composable
fun SessionListItem(
    session: DebugSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.promptType,
                    style = MaterialTheme.typography.titleSmall
                )
                if (session.isCompleted()) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Text(
                text = session.startTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (session.clientName != null) {
                Text(
                    text = session.clientName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    val clipboard = rememberClipboardManager()

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
                    SessionInfoRow("Session ID", session.id.take(8))
                    SessionInfoRow("Correlation ID", session.correlationId?.take(8) ?: "N/A")
                    SessionInfoRow("Type", session.promptType)
                    SessionInfoRow("Client", session.clientName ?: "System")
                    SessionInfoRow("Started", session.startTime.format(formatter))
                    if (session.isCompleted()) {
                        val duration = session.completionTime?.let {
                            val durationMs = java.time.Duration.between(session.startTime, it).toMillis()
                            "${durationMs}ms"
                        } ?: "N/A"
                        SessionInfoRow("Duration", duration)
                        SessionInfoRow("Status", "COMPLETED ✓")
                    } else {
                        SessionInfoRow("Status", "STREAMING...")
                    }
                }
            }

            // System Prompt
            com.jervis.ui.util.CopyableTextCard(
                title = "System Prompt",
                content = session.systemPrompt,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                useMonospace = true
            )

            // User Prompt
            com.jervis.ui.util.CopyableTextCard(
                title = "User Prompt",
                content = session.userPrompt,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                useMonospace = true
            )
        }

        VerticalDivider()

        // Right side - Response
        Column(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Response status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Streaming Response",
                    style = MaterialTheme.typography.titleMedium
                )
                if (!session.isCompleted()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            HorizontalDivider()

            // Response content
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Response",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(session.responseBuffer)) },
                            enabled = session.responseBuffer.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text(
                                "📋",
                                style = MaterialTheme.typography.titleMedium
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
