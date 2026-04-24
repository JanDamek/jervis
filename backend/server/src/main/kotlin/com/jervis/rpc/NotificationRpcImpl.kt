package com.jervis.rpc

import com.jervis.rpc.NotificationRpcImpl
import com.jervis.dto.error.ErrorNotificationDto
import com.jervis.dto.events.JervisEvent
import com.jervis.service.notification.INotificationService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class NotificationRpcImpl : INotificationService {
    private val logger = KotlinLogging.logger {}

    // Store notification streams per client
    private val eventStreams = ConcurrentHashMap<String, MutableSharedFlow<JervisEvent>>()

    // Last-active timestamp per client — updated on every kRPC event subscription
    // and used by pods to check whether the user is still around off-hours.
    private val lastActiveAt = ConcurrentHashMap<String, Instant>()

    fun markActive(clientId: String) {
        lastActiveAt[clientId] = Instant.now()
    }

    fun lastActiveAt(clientId: String): Instant? = lastActiveAt[clientId]

    override fun subscribeToEvents(clientId: String): Flow<JervisEvent> {
        logger.info { "Client subscribing to event stream: $clientId" }
        markActive(clientId)

        val sharedFlow = eventStreams.getOrPut(clientId) {
            MutableSharedFlow(
                replay = 10,
                extraBufferCapacity = 100,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        return sharedFlow.asSharedFlow()
    }

    /**
     * Internal method to emit errors to client streams.
     * Called by ErrorLogService when errors are recorded.
     */
    suspend fun emitError(clientId: String, error: ErrorNotificationDto) {
        logger.debug { "Emitting error to client $clientId: ${error.message}" }

        // Emit to event stream
        emitEvent(clientId, JervisEvent.ErrorNotification(
            id = error.id,
            severity = error.severity,
            message = error.message,
            clientId = error.clientId,
            projectId = error.projectId,
            timestamp = error.timestamp
        ))
    }

    suspend fun emitUserTaskCreated(
        clientId: String,
        taskId: String,
        title: String,
        interruptAction: String? = null,
        interruptDescription: String? = null,
        isApproval: Boolean = false,
        projectId: String? = null,
        isError: Boolean = false,
        errorDetail: String? = null,
        chatApprovalAction: String? = null,
        connectionName: String? = null,
        mfaType: String? = null,
        mfaNumber: String? = null,
        ephemeralPromptId: String? = null,
        ephemeralPromptKind: String? = null,
    ) {
        emitEvent(clientId, JervisEvent.UserTaskCreated(
            clientId = clientId,
            taskId = taskId,
            title = title,
            timestamp = java.time.Instant.now().toString(),
            interruptAction = interruptAction,
            interruptDescription = interruptDescription,
            isApproval = isApproval,
            projectId = projectId,
            isError = isError,
            errorDetail = errorDetail,
            chatApprovalAction = chatApprovalAction,
            connectionName = connectionName,
            mfaType = mfaType,
            mfaNumber = mfaNumber,
            ephemeralPromptId = ephemeralPromptId,
            ephemeralPromptKind = ephemeralPromptKind,
        ))
    }

    /**
     * Check if any client app is actively subscribed to events via kRPC WebSocket.
     * Used to decide whether to send FCM push (skip if app is connected and will get in-app dialog).
     */
    fun hasActiveSubscribers(clientId: String): Boolean =
        (eventStreams[clientId]?.subscriptionCount?.value ?: 0) > 0

    suspend fun emitUserTaskCancelled(clientId: String, taskId: String, title: String) {
        emitEvent(clientId, JervisEvent.UserTaskCancelled(
            clientId = clientId,
            taskId = taskId,
            title = title,
            timestamp = java.time.Instant.now().toString()
        ))
    }

    suspend fun emitPendingTaskCreated(taskId: String, type: String) {
        // Emit to all connected clients as pending tasks are global
        val event = JervisEvent.PendingTaskCreated(
            taskId = taskId,
            type = type,
            timestamp = java.time.Instant.now().toString()
        )
        // Important: this sends to all clients, which is correct for global pending tasks
        eventStreams.keys().asSequence().forEach { clientId ->
            emitEvent(clientId, event)
        }
    }

    // Meeting state / transcription / correction progress used to fan out
    // to every connected UI client over the global event stream. That made
    // sense back when MeetingScreen was the default landing page; now the
    // global stream is reserved for cross-cutting events (UserTaskCreated,
    // OrchestratorTaskProgress, ApprovalRequired, …) and the meeting UI
    // pulls fresh state via `loadMeeting(id)` / `loadTimeline()` on open.
    // Spamming idle clients with N MeetingStateChanged events per meeting
    // tick clutters logs (`Received global event: MeetingStateChanged …`)
    // for zero benefit. Keep the methods as no-ops so existing call sites
    // compile; restore per-meeting subscription if/when live progress is
    // actually wanted somewhere.
    @Suppress("UNUSED_PARAMETER")
    suspend fun emitMeetingStateChanged(
        meetingId: String,
        clientId: String,
        newState: String,
        title: String? = null,
        errorMessage: String? = null,
    ) {
        // intentionally dropped — see comment above
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun emitMeetingTranscriptionProgress(
        meetingId: String,
        clientId: String,
        percent: Double,
        segmentsDone: Int,
        elapsedSeconds: Double,
        lastSegmentText: String? = null,
    ) {
        // intentionally dropped — see comment above
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun emitMeetingCorrectionProgress(
        meetingId: String,
        clientId: String,
        percent: Double,
        chunksDone: Int,
        totalChunks: Int,
        message: String? = null,
        tokensGenerated: Int = 0,
    ) {
        // intentionally dropped — see comment above
    }

    suspend fun emitQualificationProgress(
        taskId: String,
        clientId: String,
        message: String,
        step: String,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val now = Instant.now()
        val event = JervisEvent.QualificationProgress(
            taskId = taskId,
            clientId = clientId,
            message = message,
            step = step,
            timestamp = now.toString(),
            metadata = metadata + ("epochMs" to now.toEpochMilli().toString()),
        )
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    suspend fun emitOrchestratorTaskProgress(
        taskId: String,
        clientId: String,
        node: String,
        message: String,
        percent: Double = 0.0,
        goalIndex: Int = 0,
        totalGoals: Int = 0,
        stepIndex: Int = 0,
        totalSteps: Int = 0,
    ) {
        val event = JervisEvent.OrchestratorTaskProgress(
            taskId = taskId,
            clientId = clientId,
            node = node,
            message = message,
            percent = percent,
            goalIndex = goalIndex,
            totalGoals = totalGoals,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            timestamp = Instant.now().toString(),
        )
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    suspend fun emitOrchestratorTaskStatusChange(
        taskId: String,
        clientId: String,
        threadId: String,
        status: String,
        summary: String? = null,
        error: String? = null,
        interruptAction: String? = null,
        interruptDescription: String? = null,
        branch: String? = null,
        artifacts: List<String> = emptyList(),
    ) {
        val event = JervisEvent.OrchestratorTaskStatusChange(
            taskId = taskId,
            clientId = clientId,
            threadId = threadId,
            status = status,
            summary = summary,
            error = error,
            interruptAction = interruptAction,
            interruptDescription = interruptDescription,
            branch = branch,
            artifacts = artifacts,
            timestamp = Instant.now().toString(),
        )
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    suspend fun emitConnectionStateChanged(
        clientId: String,
        connectionId: String,
        connectionName: String,
        newState: String,
        message: String,
    ) {
        val event = JervisEvent.ConnectionStateChanged(
            connectionId = connectionId,
            connectionName = connectionName,
            newState = newState,
            message = message,
            timestamp = Instant.now().toString(),
        )
        emitEvent(clientId, event)
    }

    suspend fun emitMeetingHelperMessage(
        meetingId: String,
        message: com.jervis.dto.meeting.HelperMessageDto,
    ) {
        val event = JervisEvent.MeetingHelperMessage(
            meetingId = meetingId,
            type = message.type.name.lowercase(),
            text = message.text,
            context = message.context,
            fromLang = message.fromLang,
            toLang = message.toLang,
            timestamp = message.timestamp,
        )
        // Broadcast to all connected clients (meeting helper is user-global)
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    /**
     * Emit a generic event to a client.
     */
    suspend fun emitEvent(clientId: String, event: JervisEvent) {
        logger.debug { "Emitting event to client $clientId: ${event::class.simpleName}" }
        eventStreams[clientId]?.emit(event)
    }

}
