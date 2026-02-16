package com.jervis.rpc

import com.jervis.dto.ErrorNotificationDto
import com.jervis.dto.events.JervisEvent
import com.jervis.service.INotificationService
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

    override fun subscribeToEvents(clientId: String): Flow<JervisEvent> {
        logger.info { "Client subscribing to event stream: $clientId" }

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

    suspend fun emitMeetingStateChanged(
        meetingId: String,
        clientId: String,
        newState: String,
        title: String? = null,
        errorMessage: String? = null,
    ) {
        val event = JervisEvent.MeetingStateChanged(
            meetingId = meetingId,
            clientId = clientId,
            newState = newState,
            title = title,
            errorMessage = errorMessage,
            timestamp = Instant.now().toString(),
        )
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    suspend fun emitMeetingTranscriptionProgress(
        meetingId: String,
        clientId: String,
        percent: Double,
        segmentsDone: Int,
        elapsedSeconds: Double,
        lastSegmentText: String? = null,
    ) {
        val event = JervisEvent.MeetingTranscriptionProgress(
            meetingId = meetingId,
            clientId = clientId,
            percent = percent,
            segmentsDone = segmentsDone,
            elapsedSeconds = elapsedSeconds,
            lastSegmentText = lastSegmentText,
            timestamp = Instant.now().toString(),
        )
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }

    suspend fun emitMeetingCorrectionProgress(
        meetingId: String,
        clientId: String,
        percent: Double,
        chunksDone: Int,
        totalChunks: Int,
        message: String? = null,
        tokensGenerated: Int = 0,
    ) {
        val event = JervisEvent.MeetingCorrectionProgress(
            meetingId = meetingId,
            clientId = clientId,
            percent = percent,
            chunksDone = chunksDone,
            totalChunks = totalChunks,
            message = message,
            tokensGenerated = tokensGenerated,
            timestamp = Instant.now().toString(),
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

    /**
     * Emit a generic event to a client.
     */
    suspend fun emitEvent(clientId: String, event: JervisEvent) {
        logger.debug { "Emitting event to client $clientId: ${event::class.simpleName}" }
        eventStreams[clientId]?.emit(event)
    }
}
