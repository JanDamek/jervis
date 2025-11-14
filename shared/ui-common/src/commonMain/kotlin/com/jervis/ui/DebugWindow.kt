package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.events.DebugEventDto
import com.jervis.ui.util.rememberClipboardManager
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.*

/**
 * Helper function to get current LocalDateTime in a multiplatform way
 */
internal expect fun currentLocalDateTime(): LocalDateTime

/**
 * Interface for providing debug events stream
 */
interface DebugEventsProvider {
    val debugEventsFlow: Flow<DebugEventDto>?
}

/**
 * Base trace event for any action in the system
 */
sealed class TraceEvent(
    val id: String,
    val timestamp: LocalDateTime,
    val eventType: String,
    val status: EventStatus = EventStatus.IN_PROGRESS
) {
    enum class EventStatus { IN_PROGRESS, COMPLETED, FAILED }

    abstract fun getListItemLabel(): String
    abstract fun getDetailContent(): @Composable () -> Unit
}

/**
 * LLM Session trace event
 */
data class LLMSessionEvent(
    val sessionId: String,
    val promptType: String,
    val systemPrompt: String,
    val userPrompt: String,
    val startTime: LocalDateTime,
    val clientId: String? = null,
    val clientName: String? = null,
    var responseBuffer: String = "",
    var completionTime: LocalDateTime? = null,
    var currentStatus: EventStatus = EventStatus.IN_PROGRESS
) : TraceEvent(sessionId, startTime, "LLM Call", currentStatus) {

    override fun getListItemLabel(): String = buildString {
        append("🤖 LLM: $promptType")
        when (currentStatus) {
            EventStatus.COMPLETED -> append(" ✓")
            EventStatus.FAILED -> append(" ✗")
            EventStatus.IN_PROGRESS -> append(" ⏳")
        }
    }

    override fun getDetailContent(): @Composable () -> Unit = {
        LLMSessionDetail(this)
    }
}

/**
 * Generic event for task flow steps (qualification, service calls, etc.)
 */
data class TaskFlowEvent(
    val eventId: String,
    val eventName: String,
    val eventTime: LocalDateTime,
    val details: String,
    var currentStatus: EventStatus = EventStatus.COMPLETED
) : TraceEvent(eventId, eventTime, eventName, currentStatus) {

    override fun getListItemLabel(): String = buildString {
        val icon = when (eventName) {
            "TaskCreated" -> "📝"
            "TaskStateTransition" -> "🔄"
            "QualificationStart" -> "🔍"
            "QualificationDecision" -> "⚖️"
            "GpuTaskPickup" -> "⚡"
            "PlanCreated" -> "📋"
            "PlanStatusChanged" -> "🔄"
            "PlanStepAdded" -> "➕"
            "StepExecutionStart" -> "▶️"
            "StepExecutionCompleted" -> "✅"
            "FinalizerStart" -> "🏁"
            "FinalizerComplete" -> "🎉"
            else -> "📌"
        }
        append("$icon $eventName")
    }

    override fun getDetailContent(): @Composable () -> Unit = {
        TaskFlowEventDetail(this)
    }
}

/**
 * Correlation trace containing all events for a single correlationId
 */
data class CorrelationTrace(
    val correlationId: String,
    val events: MutableList<TraceEvent> = mutableListOf(),
    var clientName: String? = null,
    val startTime: LocalDateTime = currentLocalDateTime()
) {
    fun getTabLabel(): String {
        val shortId = correlationId.take(8)
        val eventCount = events.size
        val inProgress = events.count { it.status == TraceEvent.EventStatus.IN_PROGRESS }

        // Get description from the LAST event (oldest, since we add to beginning)
        val firstEventDesc = events.lastOrNull()?.let { event ->
            when (event) {
                is LLMSessionEvent -> event.promptType
                is TaskFlowEvent -> event.eventName
                else -> "Unknown"
            }
        } ?: "Empty"

        val statusIcon = if (inProgress > 0) " ⏳" else " ✓"

        return "$firstEventDesc [$shortId] ($eventCount)$statusIcon"
    }

    fun isCompleted(): Boolean = events.all { it.status != TraceEvent.EventStatus.IN_PROGRESS }
}

/**
 * Debug window with correlationId-based tracing
 */
@Composable
fun DebugWindow(eventsProvider: DebugEventsProvider) {
    val correlationTraces = remember { mutableStateMapOf<String, CorrelationTrace>() }
    var selectedTraceIndex by remember { mutableStateOf(0) }
    var selectedEvent by remember { mutableStateOf<TraceEvent?>(null) }
    var followLatestEvent by remember { mutableStateOf(false) }

    // Process debug events from WebSocket flow
    LaunchedEffect(eventsProvider) {
        eventsProvider.debugEventsFlow?.collect { event ->
            when (event) {
                is DebugEventDto.SessionStarted -> {
                    val correlationId = event.correlationId ?: event.sessionId

                    val llmEvent = LLMSessionEvent(
                        sessionId = event.sessionId,
                        promptType = event.promptType,
                        systemPrompt = event.systemPrompt,
                        userPrompt = event.userPrompt,
                        startTime = currentLocalDateTime(),
                        clientId = event.clientId,
                        clientName = event.clientName
                    )

                    val trace = correlationTraces.getOrPut(correlationId) {
                        CorrelationTrace(correlationId, clientName = event.clientName)
                    }

                    if (trace.clientName == null && event.clientName != null) {
                        trace.clientName = event.clientName
                    }

                    trace.events.add(0, llmEvent)
                    correlationTraces[correlationId] = trace.copy(events = trace.events.toMutableList())

                    // Auto-follow: select this event if follow mode is on and this trace is selected
                    if (followLatestEvent) {
                        val currentTraces = correlationTraces.values.sortedByDescending { it.startTime }
                        val selectedTrace = currentTraces.getOrNull(selectedTraceIndex)
                        if (selectedTrace?.correlationId == correlationId) {
                            selectedEvent = llmEvent
                        }
                    }
                }

                is DebugEventDto.ResponseChunkDto -> {
                    correlationTraces.values.forEach { trace ->
                        val eventIndex = trace.events.indexOfFirst {
                            it is LLMSessionEvent && it.sessionId == event.sessionId
                        }
                        if (eventIndex >= 0) {
                            val llmEvent = trace.events[eventIndex] as LLMSessionEvent
                            val updatedEvent = llmEvent.copy(
                                responseBuffer = llmEvent.responseBuffer + event.chunk
                            )
                            val updatedEvents = trace.events.toMutableList()
                            updatedEvents[eventIndex] = updatedEvent
                            correlationTraces[trace.correlationId] = trace.copy(events = updatedEvents)

                            if (selectedEvent?.id == event.sessionId) {
                                selectedEvent = updatedEvent
                            }
                        }
                    }
                }

                is DebugEventDto.SessionCompletedDto -> {
                    correlationTraces.values.forEach { trace ->
                        val eventIndex = trace.events.indexOfFirst {
                            it is LLMSessionEvent && it.sessionId == event.sessionId
                        }
                        if (eventIndex >= 0) {
                            val llmEvent = trace.events[eventIndex] as LLMSessionEvent
                            val updatedEvent = llmEvent.copy(
                                completionTime = currentLocalDateTime(),
                                currentStatus = TraceEvent.EventStatus.COMPLETED
                            )
                            val updatedEvents = trace.events.toMutableList()
                            updatedEvents[eventIndex] = updatedEvent
                            correlationTraces[trace.correlationId] = trace.copy(events = updatedEvents)

                            if (selectedEvent?.id == event.sessionId) {
                                selectedEvent = updatedEvent
                            }
                        }
                    }
                }

                // Task flow events - now handled
                is DebugEventDto.TaskCreated -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "TaskCreated",
                        "Task ${event.taskId} created (type: ${event.taskType}, state: ${event.state})",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.TaskStateTransition -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "TaskStateTransition",
                        "Task ${event.taskId}: ${event.fromState} → ${event.toState}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.QualificationStart -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "QualificationStart",
                        "Started qualification for task ${event.taskId} (type: ${event.taskType})",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.QualificationDecision -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "QualificationDecision",
                        "Decision: ${event.decision} (duration: ${event.duration}ms)\nReason: ${event.reason}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.GpuTaskPickup -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "GpuTaskPickup",
                        "GPU picked up task ${event.taskId} (type: ${event.taskType}, state: ${event.state})",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.PlanCreated -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "PlanCreated",
                        "Plan ${event.planId} created\nInstruction: ${event.taskInstruction}\nBackground: ${event.backgroundMode}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.PlanStatusChanged -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "PlanStatusChanged",
                        "Plan ${event.planId} status changed to: ${event.status}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.PlanStepAdded -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "PlanStepAdded",
                        "Step #${event.order} added to plan ${event.planId}\nTool: ${event.toolName}\nInstruction: ${event.instruction}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.StepExecutionStart -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "StepExecutionStart",
                        "Started step #${event.order} in plan ${event.planId}\nTool: ${event.toolName}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.StepExecutionCompleted -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "StepExecutionCompleted",
                        "Completed step in plan ${event.planId}\nTool: ${event.toolName}\nStatus: ${event.status}\nResult type: ${event.resultType}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.FinalizerStart -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "FinalizerStart",
                        "Finalizer started for plan ${event.planId}\nTotal: ${event.totalSteps}, Completed: ${event.completedSteps}, Failed: ${event.failedSteps}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
                is DebugEventDto.FinalizerComplete -> {
                    addTaskFlowEvent(correlationTraces, event.correlationId, "FinalizerComplete",
                        "Finalizer completed for plan ${event.planId}\nAnswer length: ${event.answerLength}",
                        followLatestEvent, selectedTraceIndex) { selectedEvent = it }
                }
            }
        }
    }

    // Convert traces to sorted list (newest first)
    val currentTraces = correlationTraces.values.sortedByDescending { it.startTime }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (currentTraces.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "No Traces Yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "All system events will appear here grouped by correlationId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Safely clamp trace index to valid range
                val safeTraceIndex = selectedTraceIndex.coerceIn(0, currentTraces.size - 1)
                if (safeTraceIndex != selectedTraceIndex) {
                    selectedTraceIndex = safeTraceIndex
                }

                // Trace tabs
                Row(modifier = Modifier.fillMaxWidth()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = safeTraceIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        currentTraces.forEachIndexed { index, trace ->
                            Tab(
                                selected = safeTraceIndex == index,
                                onClick = {
                                    selectedTraceIndex = index
                                    selectedEvent = null // Show event list, not detail
                                },
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(trace.getTabLabel())
                                        IconButton(
                                            onClick = {
                                                correlationTraces.remove(trace.correlationId)
                                                if (selectedTraceIndex >= correlationTraces.size && selectedTraceIndex > 0) {
                                                    selectedTraceIndex = correlationTraces.size - 1
                                                }
                                                selectedEvent = null
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Text(
                                                "✕",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Controls: Follow mode + Close All Completed
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Follow latest event checkbox
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = followLatestEvent,
                                onCheckedChange = { followLatestEvent = it }
                            )
                            Text("Follow Latest Event", style = MaterialTheme.typography.bodyMedium)
                        }

                        // Close All Completed button
                        if (correlationTraces.values.any { it.isCompleted() }) {
                            Button(
                                onClick = {
                                    val completedIds = correlationTraces.values
                                        .filter { it.isCompleted() }
                                        .map { it.correlationId }
                                    completedIds.forEach { correlationTraces.remove(it) }
                                    if (selectedTraceIndex >= correlationTraces.size && correlationTraces.isNotEmpty()) {
                                        selectedTraceIndex = correlationTraces.size - 1
                                    } else if (correlationTraces.isEmpty()) {
                                        selectedTraceIndex = 0
                                    }
                                    selectedEvent = null
                                }
                            ) {
                                Text("Close All Completed")
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Trace content with event list
                val selectedTrace = currentTraces.getOrNull(safeTraceIndex)
                if (selectedTrace != null) {
                    CorrelationTraceContent(
                        trace = selectedTrace,
                        selectedEvent = selectedEvent,
                        onEventSelected = { selectedEvent = it }
                    )
                }
            }
        }
    }
}

/**
 * Helper function to add task flow events
 */
private fun addTaskFlowEvent(
    traces: MutableMap<String, CorrelationTrace>,
    correlationId: String,
    eventName: String,
    details: String,
    followLatestEvent: Boolean,
    selectedTraceIndex: Int,
    onEventSelected: (TraceEvent) -> Unit
) {
    val trace = traces.getOrPut(correlationId) {
        CorrelationTrace(correlationId)
    }

    val event = TaskFlowEvent(
        eventId = "${correlationId}_${eventName}_${currentLocalDateTime()}",
        eventName = eventName,
        eventTime = currentLocalDateTime(),
        details = details
    )

    trace.events.add(0, event)
    traces[correlationId] = trace.copy(events = trace.events.toMutableList())

    // Auto-follow: select this event if follow mode is on and this trace is selected
    if (followLatestEvent) {
        val currentTraces = traces.values.sortedByDescending { it.startTime }
        val selectedTrace = currentTraces.getOrNull(selectedTraceIndex)
        if (selectedTrace?.correlationId == correlationId) {
            onEventSelected(event)
        }
    }
}

/**
 * Content for a correlation trace showing list of events and selected event detail
 * Uses responsive layout: side-by-side when wide enough, stacked navigation when narrow
 */
@Composable
fun CorrelationTraceContent(
    trace: CorrelationTrace,
    selectedEvent: TraceEvent?,
    onEventSelected: (TraceEvent?) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideLayout = maxWidth > 1000.dp

        if (isWideLayout) {
            // Wide layout: event list on left (narrow), detail on right
            Row(modifier = Modifier.fillMaxSize()) {
                // Event list - narrow column on left
                Column(
                    modifier = Modifier
                        .width(350.dp)
                        .fillMaxHeight()
                ) {
                    Text(
                        text = "Events (${trace.events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(trace.events) { event ->
                            EventListItem(
                                event = event,
                                isSelected = selectedEvent?.id == event.id,
                                onClick = { onEventSelected(event) }
                            )
                        }
                    }
                }

                VerticalDivider()

                // Detail panel on right
                Box(modifier = Modifier.fillMaxSize()) {
                    if (selectedEvent != null) {
                        selectedEvent.getDetailContent()()
                    } else {
                        // Empty state when no event selected
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Select an event to view details",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Narrow layout: show either list or detail with back button
            if (selectedEvent != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Back button bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onEventSelected(null) }) {
                            Text("← Back")
                        }
                        Text(
                            selectedEvent.getListItemLabel(),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    HorizontalDivider()

                    // Full-screen detail
                    selectedEvent.getDetailContent()()
                }
            } else {
                // Show event list
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Events (${trace.events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(trace.events) { event ->
                            EventListItem(
                                event = event,
                                isSelected = false,
                                onClick = { onEventSelected(event) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single event list item
 */
@Composable
fun EventListItem(
    event: TraceEvent,
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
            Text(
                text = event.getListItemLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Text(
                text = formatDateTime(event.timestamp, "HH:mm:ss"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Detail view for LLM session event
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
fun LLMSessionDetail(session: LLMSessionEvent) {
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
                    SessionInfoRow("Session ID", session.sessionId.take(8))
                    SessionInfoRow("Type", session.promptType)
                    SessionInfoRow("Client", session.clientName ?: "System")
                    SessionInfoRow("Started", formatDateTime(session.startTime))
                    if (session.currentStatus == TraceEvent.EventStatus.COMPLETED) {
                        val duration = session.completionTime?.let {
                            val durationInstant = it.toInstant(TimeZone.currentSystemDefault()) -
                                session.startTime.toInstant(TimeZone.currentSystemDefault())
                            val durationMs = durationInstant.inWholeMilliseconds
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
                if (session.currentStatus == TraceEvent.EventStatus.IN_PROGRESS) {
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

/**
 * Detail view for task flow event
 */
@Composable
fun TaskFlowEventDetail(event: TaskFlowEvent) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Event info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Event Info",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                SessionInfoRow("Event Type", event.eventName)
                SessionInfoRow("Time", formatDateTime(event.eventTime))
                SessionInfoRow("Status", event.currentStatus.name)
            }
        }

        // Event details
        com.jervis.ui.util.CopyableTextCard(
            title = "Details",
            content = event.details,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
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

/**
 * Helper function to format LocalDateTime to string
 */
private fun formatDateTime(dateTime: LocalDateTime, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    return when (pattern) {
        "yyyy-MM-dd HH:mm:ss" -> "${dateTime.year}-${dateTime.monthNumber.toString().padStart(2, '0')}-${dateTime.dayOfMonth.toString().padStart(2, '0')} ${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}"
        "HH:mm:ss" -> "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}"
        else -> dateTime.toString()
    }
}
