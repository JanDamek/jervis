package com.jervis.rpc

import com.jervis.rpc.NotificationRpcImpl
import com.jervis.dto.error.ErrorNotificationDto
import com.jervis.dto.events.JervisEvent
import com.jervis.dto.task.TaskStateEnum
import com.jervis.service.notification.INotificationService
import com.jervis.task.TaskRepository
import com.jervis.common.types.ClientId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class NotificationRpcImpl(
    @Autowired(required = false) private val taskRepository: TaskRepository? = null,
) : INotificationService {
    private val logger = KotlinLogging.logger {}
    private val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // Replay all currently-pending USER_TASK rows to the (re)connecting
        // client. The flow's own replay buffer (last 10 events) handles
        // recent live events, but a TaskDocument that has been sitting in
        // USER_TASK state for hours — e.g. a login_consent push the user
        // ignored on iOS while sandbox APNs was misbehaving — would be
        // outside that buffer. Without this replay the chat dialog never
        // surfaces on cold start, even though the server-side gate is
        // still open.
        replayScope.launch {
            replayPendingUserTasks(clientId, sharedFlow)
        }

        return sharedFlow.asSharedFlow()
    }

    private suspend fun replayPendingUserTasks(
        clientId: String,
        flow: MutableSharedFlow<JervisEvent>,
    ) {
        val repo = taskRepository ?: return
        try {
            val cid = ClientId(ObjectId(clientId))
            val pending = repo
                .findByClientIdAndStateOrderByCreatedAtAsc(cid, TaskStateEnum.USER_TASK)
                .toList()
            if (pending.isEmpty()) return
            logger.info { "Replaying ${pending.size} pending USER_TASK(s) to client $clientId" }
            for (task in pending) {
                val sourceUrnValue = task.sourceUrn.value
                val isLoginConsent = task.actionType == "login_consent" ||
                    sourceUrnValue.startsWith("login_consent::")
                val interruptDescription = if (isLoginConsent) {
                    val requestId = sourceUrnValue.removePrefix("login_consent::")
                        .takeIf { it.isNotBlank() && it != sourceUrnValue }
                    if (requestId != null) "requestId=$requestId" else task.userQuestionContext
                } else {
                    task.userQuestionContext
                }
                flow.emit(
                    JervisEvent.UserTaskCreated(
                        clientId = clientId,
                        taskId = task.id.toString(),
                        title = task.pendingUserQuestion ?: task.taskName,
                        timestamp = Instant.now().toString(),
                        interruptAction = task.actionType,
                        interruptDescription = interruptDescription,
                        isApproval = false,
                        projectId = task.projectId?.toString(),
                    ),
                )
            }
        } catch (e: IllegalArgumentException) {
            logger.warn { "Replay skipped — invalid clientId: $clientId" }
        } catch (e: Exception) {
            logger.warn(e) { "Replay of pending USER_TASKs failed for $clientId" }
        }
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

    /**
     * Emit an AgentJobStateChanged event. Coding agents are global background
     * jobs (the sidebar Background section is global scope per spec), so we
     * fan out to every connected client instead of routing by clientId. UIs
     * can filter on the event's clientId/projectId fields to scope the
     * sidebar group.
     */
    suspend fun emitAgentJobStateChanged(event: JervisEvent.AgentJobStateChanged) {
        logger.debug {
            "AgentJobStateChanged | agentJobId=${event.agentJobId} state=${event.state} " +
                "client=${event.clientId} project=${event.projectId}"
        }
        eventStreams.keys().asSequence().forEach { id ->
            emitEvent(id, event)
        }
    }
}
