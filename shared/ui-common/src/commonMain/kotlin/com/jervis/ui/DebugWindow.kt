package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.jervis.dto.events.getCorrelationId
import com.jervis.dto.events.getEventDetails
import com.jervis.dto.events.getEventName
import com.jervis.ui.util.rememberClipboardManager
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.*

/**
 * Helper function to get current LocalDateTime in a multiplatform way
 */
internal expect fun currentLocalDateTime(): LocalDateTime

// Autoscroll configuration shared via CompositionLocal so details can react without prop drilling
private data class AutoScrollConfig(
    val enabled: Boolean,
    val version: Int,
    val disable: () -> Unit,
)

private val LocalAutoScrollConfig = staticCompositionLocalOf<AutoScrollConfig?> { null }

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
    val status: EventStatus = EventStatus.IN_PROGRESS,
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
    var currentStatus: EventStatus = EventStatus.IN_PROGRESS,
) : TraceEvent(sessionId, startTime, "LLM Call", currentStatus) {
    override fun getListItemLabel(): String =
        buildString {
            append("🤖 LLM: $promptType")
            when (currentStatus) {
                EventStatus.COMPLETED -> append(" ✓")
                EventStatus.FAILED -> append(" ✗")
                EventStatus.IN_PROGRESS -> append(" ⏳")
            }
        }

    override fun getDetailContent(): @Composable () -> Unit =
        {
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
    var currentStatus: EventStatus = EventStatus.COMPLETED,
) : TraceEvent(eventId, eventTime, eventName, currentStatus) {
    override fun getListItemLabel(): String =
        buildString {
            val icon =
                when (eventName) {
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

    override fun getDetailContent(): @Composable () -> Unit =
        {
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
    val startTime: LocalDateTime = currentLocalDateTime(),
) {
    fun getTabLabel(): String {
        val shortId = correlationId.take(8)
        val eventCount = events.size
        val inProgress = events.count { it.status == TraceEvent.EventStatus.IN_PROGRESS }

        // Get description from the LAST event (oldest, since we add to beginning)
        val firstEventDesc =
            events.lastOrNull()?.let { event ->
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
 * @param eventsProvider Provider of debug events from WebSocket
 * @param onBack Optional callback for mobile back button (null for desktop standalone window)
 */
@Composable
fun DebugWindow(
    eventsProvider: DebugEventsProvider,
    onBack: (() -> Unit)? = null,
) {
    val correlationTraces = remember { mutableStateMapOf<String, CorrelationTrace>() }
    // Keep selection stable by correlationId so it doesn't jump when list order changes
    var selectedTraceId by remember { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<TraceEvent?>(null) }
    var followLatestEvent by remember { mutableStateOf(false) }
    // Auto-scroll control for streaming response
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var autoScrollVersion by remember { mutableStateOf(0) }

    // Process debug events from WebSocket flow
    LaunchedEffect(eventsProvider) {
        eventsProvider.debugEventsFlow?.collect { event ->
            when (event) {
                is DebugEventDto.SessionStarted -> {
                    val correlationId = event.correlationId ?: event.sessionId

                    val llmEvent =
                        LLMSessionEvent(
                            sessionId = event.sessionId,
                            promptType = event.promptType,
                            systemPrompt = event.systemPrompt,
                            userPrompt = event.userPrompt,
                            startTime = currentLocalDateTime(),
                            clientId = event.clientId,
                            clientName = event.clientName,
                        )

                    val trace =
                        correlationTraces.getOrPut(correlationId) {
                            CorrelationTrace(correlationId, clientName = event.clientName)
                        }

                    if (trace.clientName == null && event.clientName != null) {
                        trace.clientName = event.clientName
                    }

                    trace.events.add(0, llmEvent)
                    correlationTraces[correlationId] = trace.copy(events = trace.events.toMutableList())

                    // Auto-follow: select this event if follow mode is on and this trace is selected
                    if (followLatestEvent && selectedTraceId == correlationId) {
                        selectedEvent = llmEvent
                    }
                }

                is DebugEventDto.ResponseChunkDto -> {
                    correlationTraces.values.forEach { trace ->
                        val eventIndex =
                            trace.events.indexOfFirst {
                                it is LLMSessionEvent && it.sessionId == event.sessionId
                            }
                        if (eventIndex >= 0) {
                            val llmEvent = trace.events[eventIndex] as LLMSessionEvent
                            val updatedEvent =
                                llmEvent.copy(
                                    responseBuffer = llmEvent.responseBuffer + event.chunk,
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
                        val eventIndex =
                            trace.events.indexOfFirst {
                                it is LLMSessionEvent && it.sessionId == event.sessionId
                            }
                        if (eventIndex >= 0) {
                            val llmEvent = trace.events[eventIndex] as LLMSessionEvent
                            val updatedEvent =
                                llmEvent.copy(
                                    completionTime = currentLocalDateTime(),
                                    currentStatus = TraceEvent.EventStatus.COMPLETED,
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

                // Task flow events - generic handler using extension functions
                else -> {
                    val eventName = event.getEventName()
                    val eventDetails = event.getEventDetails()
                    val correlationId = event.getCorrelationId()

                    if (eventName != null && eventDetails != null && correlationId != null) {
                        addTaskFlowEvent(
                            correlationTraces,
                            correlationId,
                            eventName,
                            eventDetails,
                            followLatestEvent,
                            selectedTraceId,
                        ) { selectedEvent = it }
                    }
                }
            }
        }
    }

    // Convert traces to sorted list (newest first)
    val currentTraces = correlationTraces.values.sortedByDescending { it.startTime }

    // Ensure selectedTraceId is initialized and kept valid
    if (selectedTraceId == null && currentTraces.isNotEmpty()) {
        selectedTraceId = currentTraces.first().correlationId
    }
    if (selectedTraceId != null && currentTraces.none { it.correlationId == selectedTraceId }) {
        selectedTraceId = currentTraces.firstOrNull()?.correlationId
        selectedEvent = null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (onBack != null) Modifier.padding(16.dp) else Modifier)
        ) {
            // Header with back button (only for mobile)
            if (onBack != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("← Back") }
                    Spacer(Modifier.width(12.dp))
                    Text("Debug Console", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (currentTraces.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "No Traces Yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "All system events will appear here grouped by correlationId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Compute current selected tab index from selectedTraceId
                val safeTraceIndex =
                    selectedTraceId?.let { id -> currentTraces.indexOfFirst { it.correlationId == id } }
                        ?.takeIf { it >= 0 } ?: 0

                // Trace tabs and controls
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isNarrowScreen = maxWidth < 800.dp

                    if (isNarrowScreen) {
                        // Mobile layout: tabs full width, controls below
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = safeTraceIndex,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                currentTraces.forEachIndexed { index, trace ->
                                    Tab(
                                        selected = safeTraceIndex == index,
                                        onClick = {
                                            selectedTraceId = trace.correlationId
                                            selectedEvent = null
                                        },
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(trace.getTabLabel())
                                                IconButton(
                                                    onClick = {
                                                        val removedSelected = (selectedTraceId == trace.correlationId)
                                                        correlationTraces.remove(trace.correlationId)
                                                        if (removedSelected) {
                                                            selectedTraceId = correlationTraces.values.maxByOrNull { it.startTime }?.correlationId
                                                            selectedEvent = null
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp),
                                                ) {
                                                    Text(
                                                        "✕",
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            // Controls below tabs on mobile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Follow latest checkbox - mobile friendly
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable { followLatestEvent = !followLatestEvent }
                                        .padding(4.dp)
                                ) {
                                    Checkbox(
                                        checked = followLatestEvent,
                                        onCheckedChange = { followLatestEvent = it },
                                    )
                                    Text("Follow", style = MaterialTheme.typography.bodyMedium)
                                }

                                // Auto-scroll checkbox
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable {
                                        val newValue = !autoScrollEnabled
                                        autoScrollEnabled = newValue
                                        if (newValue) autoScrollVersion++
                                    }.padding(4.dp)
                                ) {
                                    Checkbox(
                                        checked = autoScrollEnabled,
                                        onCheckedChange = { checked ->
                                            autoScrollEnabled = checked
                                            if (checked) autoScrollVersion++
                                        },
                                    )
                                    Text("Auto-scroll", style = MaterialTheme.typography.bodyMedium)
                                }

                                // Close completed button - mobile friendly
                                if (correlationTraces.values.any { it.isCompleted() }) {
                                    Button(
                                        onClick = {
                                            val completedIds =
                                                correlationTraces.values
                                                    .filter { it.isCompleted() }
                                                    .map { it.correlationId }
                                            completedIds.forEach { correlationTraces.remove(it) }
                                            if (selectedTraceId != null && correlationTraces.values.none { it.correlationId == selectedTraceId }) {
                                                selectedTraceId = correlationTraces.values.maxByOrNull { it.startTime }?.correlationId
                                                selectedEvent = null
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text("Close Completed", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    } else {
                        // Desktop layout: tabs and controls side by side
                        Row(modifier = Modifier.fillMaxWidth()) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = safeTraceIndex,
                                modifier = Modifier.weight(1f),
                            ) {
                                currentTraces.forEachIndexed { index, trace ->
                                    Tab(
                                        selected = safeTraceIndex == index,
                                        onClick = {
                                            selectedTraceId = trace.correlationId
                                            selectedEvent = null
                                        },
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(trace.getTabLabel())
                                                IconButton(
                                                    onClick = {
                                                        val removedSelected = (selectedTraceId == trace.correlationId)
                                                        correlationTraces.remove(trace.correlationId)
                                                        if (removedSelected) {
                                                            selectedTraceId = correlationTraces.values.maxByOrNull { it.startTime }?.correlationId
                                                            selectedEvent = null
                                                        }
                                                    },
                                                    modifier = Modifier.size(20.dp),
                                                ) {
                                                    Text(
                                                        "✕",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            // Controls on right on desktop
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Checkbox(
                                        checked = followLatestEvent,
                                        onCheckedChange = { followLatestEvent = it },
                                    )
                                    Text("Follow Latest Event", style = MaterialTheme.typography.bodyMedium)
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Checkbox(
                                        checked = autoScrollEnabled,
                                        onCheckedChange = { checked ->
                                            autoScrollEnabled = checked
                                            if (checked) autoScrollVersion++ // force jump to bottom
                                        },
                                    )
                                    Text("Auto-scroll", style = MaterialTheme.typography.bodyMedium)
                                }

                                if (correlationTraces.values.any { it.isCompleted() }) {
                                    Button(
                                        onClick = {
                                            val completedIds =
                                                correlationTraces.values
                                                    .filter { it.isCompleted() }
                                                    .map { it.correlationId }
                                            completedIds.forEach { correlationTraces.remove(it) }
                                            if (selectedTraceId != null && correlationTraces.values.none { it.correlationId == selectedTraceId }) {
                                                selectedTraceId = correlationTraces.values.maxByOrNull { it.startTime }?.correlationId
                                                selectedEvent = null
                                            }
                                        },
                                    ) {
                                        Text("Close All Completed")
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Trace content with event list
                val selectedTrace = currentTraces.getOrNull(safeTraceIndex)
                if (selectedTrace != null) {
                    CompositionLocalProvider(
                        LocalAutoScrollConfig provides AutoScrollConfig(
                            enabled = autoScrollEnabled,
                            version = autoScrollVersion,
                            disable = { autoScrollEnabled = false },
                        ),
                    ) {
                        CorrelationTraceContent(
                            trace = selectedTrace,
                            selectedEvent = selectedEvent,
                            onEventSelected = { selectedEvent = it },
                        )
                    }
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
    selectedTraceId: String?,
    onEventSelected: (TraceEvent) -> Unit,
) {
    val trace =
        traces.getOrPut(correlationId) {
            CorrelationTrace(correlationId)
        }

    val event =
        TaskFlowEvent(
            eventId = "${correlationId}_${eventName}_${currentLocalDateTime()}",
            eventName = eventName,
            eventTime = currentLocalDateTime(),
            details = details,
        )

    trace.events.add(0, event)
    traces[correlationId] = trace.copy(events = trace.events.toMutableList())

    // Auto-follow: select this event if follow mode is on and this trace is selected
    if (followLatestEvent && selectedTraceId == correlationId) {
        onEventSelected(event)
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
    onEventSelected: (TraceEvent?) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideLayout = maxWidth > 1000.dp

        if (isWideLayout) {
            // Wide layout: event list on left (narrow), detail on right
            Row(modifier = Modifier.fillMaxSize()) {
                // Event list - narrow column on left
                Column(
                    modifier =
                        Modifier
                            .width(350.dp)
                            .fillMaxHeight(),
                ) {
                    Text(
                        text = "Events (${trace.events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(trace.events) { event ->
                            EventListItem(
                                event = event,
                                isSelected = selectedEvent?.id == event.id,
                                onClick = { onEventSelected(event) },
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
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Select an event to view details",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onEventSelected(null) }) {
                            Text("← Back")
                        }
                        Text(
                            selectedEvent.getListItemLabel(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    HorizontalDivider()

                    // Full-screen detail
                    selectedEvent.getDetailContent()()
                }
            } else {
                // Show event list
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                ) {
                    Text(
                        text = "Events (${trace.events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(trace.events) { event ->
                            EventListItem(
                                event = event,
                                isSelected = false,
                                onClick = { onEventSelected(event) },
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
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp), // Minimum touch target height
        onClick = onClick,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isSelected) 4.dp else 1.dp,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = event.getListItemLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                text = formatDateTime(event.timestamp, "HH:mm:ss"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isNarrowScreen = maxWidth < 800.dp

        if (isNarrowScreen) {
            // Mobile layout: everything stacked vertically
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Session info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Session Info",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                        SessionInfoRow("Session ID", session.sessionId.take(8))
                        SessionInfoRow("Type", session.promptType)
                        SessionInfoRow("Client", session.clientName ?: "System")
                        SessionInfoRow("Started", formatDateTime(session.startTime))
                        if (session.currentStatus == TraceEvent.EventStatus.COMPLETED) {
                            val duration =
                                session.completionTime?.let {
                                    val durationInstant =
                                        it.toInstant(TimeZone.currentSystemDefault()) -
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
                    useMonospace = true,
                )

                // User Prompt
                com.jervis.ui.util.CopyableTextCard(
                    title = "User Prompt",
                    content = session.userPrompt,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    useMonospace = true,
                )

                // Response
                ResponseCard(session, clipboard)
            }
        } else {
            // Desktop layout: side by side
            Row(modifier = Modifier.fillMaxSize()) {
                // Left side - Prompts
                Column(
                    modifier =
                        Modifier
                            .weight(0.5f)
                            .fillMaxHeight()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Session info card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Session Info",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                            SessionInfoRow("Session ID", session.sessionId.take(8))
                            SessionInfoRow("Type", session.promptType)
                            SessionInfoRow("Client", session.clientName ?: "System")
                            SessionInfoRow("Started", formatDateTime(session.startTime))
                            if (session.currentStatus == TraceEvent.EventStatus.COMPLETED) {
                                val duration =
                                    session.completionTime?.let {
                                        val durationInstant =
                                            it.toInstant(TimeZone.currentSystemDefault()) -
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
                        useMonospace = true,
                    )

                    // User Prompt
                    com.jervis.ui.util.CopyableTextCard(
                        title = "User Prompt",
                        content = session.userPrompt,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        useMonospace = true,
                    )
                }

                VerticalDivider()

                // Right side - Response
                Column(
                    modifier =
                        Modifier
                            .weight(0.5f)
                            .fillMaxHeight()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResponseCard(session, clipboard)
                }
            }
        }
    }
}

@Composable
private fun ResponseCard(
    session: LLMSessionEvent,
    clipboard: com.jervis.ui.util.ClipboardHandler,
) {
    // Smart autoscroll driven by global config (CompositionLocal)
    val autoConfig = LocalAutoScrollConfig.current
    val scrollState = rememberScrollState()
    val previousBufferLength = remember { mutableStateOf(0) }

    // Detect if user manually scrolled away from bottom -> disable autoscroll globally
    LaunchedEffect(scrollState.value, scrollState.maxValue, autoConfig?.enabled) {
        if (scrollState.maxValue > 0) {
            val isAtBottom = scrollState.value >= scrollState.maxValue - 50 // 50px threshold
            if (!isAtBottom && (autoConfig?.enabled == true)) {
                // user moved away from bottom; stop autoscroll
                autoConfig.disable.invoke()
            }
        }
    }

    // When auto-scroll toggled on (version bump), jump to bottom immediately
    LaunchedEffect(autoConfig?.version) {
        if (autoConfig?.enabled == true) {
            // Delay is not strictly needed; scroll to current max
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Auto-scroll when new content arrives
    LaunchedEffect(session.responseBuffer) {
        if (session.responseBuffer.length > previousBufferLength.value) {
            if (autoConfig?.enabled == true) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            previousBufferLength.value = session.responseBuffer.length
        }
    }

    Column(
        modifier = Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Response status indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Streaming Response",
                style = MaterialTheme.typography.titleMedium,
            )
            if (session.currentStatus == TraceEvent.EventStatus.IN_PROGRESS) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        HorizontalDivider()

        // Response content
        Card(
            modifier = Modifier.fillMaxSize(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Response",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(session.responseBuffer)) },
                        enabled = session.responseBuffer.isNotEmpty(),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Text(
                            "📋",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                ) {
                    if (session.responseBuffer.isEmpty()) {
                        Text(
                            "Waiting for response...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                session.responseBuffer,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
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
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Event info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Event Info",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
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
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun SessionInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Helper function to format LocalDateTime to string
 */
private fun formatDateTime(
    dateTime: LocalDateTime,
    pattern: String = "yyyy-MM-dd HH:mm:ss",
): String =
    when (pattern) {
        "yyyy-MM-dd HH:mm:ss" -> "${dateTime.year}-${dateTime.monthNumber.toString().padStart(
            2,
            '0',
        )}-${dateTime.dayOfMonth.toString().padStart(
            2,
            '0',
        )} ${dateTime.hour.toString().padStart(
            2,
            '0',
        )}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}"
        "HH:mm:ss" -> "${dateTime.hour.toString().padStart(
            2,
            '0',
        )}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}"
        else -> dateTime.toString()
    }
