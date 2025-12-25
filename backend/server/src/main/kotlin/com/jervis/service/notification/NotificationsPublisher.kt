package com.jervis.service.notification

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.notification.domain.PlanStatusChangeEvent
import com.jervis.service.notification.domain.StepCompletionEvent
import com.jervis.service.websocket.WebSocketSessionManager
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.serialization.json.Json
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import com.jervis.dto.events.PlanStatusChangeEventDto as PlanStatusChangeEventDtoD
import com.jervis.dto.events.StepCompletionEventDto as StepCompletionEventDtoD
import com.jervis.dto.events.UserDialogCloseEventDto as UserDialogCloseEventDtoD
import com.jervis.dto.events.UserDialogRequestEventDto as UserDialogRequestEventDtoD
import com.jervis.dto.events.UserTaskCancelledEventDto as UserTaskCancelledEventDtoD
import com.jervis.dto.events.UserTaskCreatedEventDto as UserTaskCreatedEventDtoD

@Component
class NotificationsPublisher(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json = Json { encodeDefaults = true }

    @EventListener
    fun onStepCompleted(event: StepCompletionEvent) {
        val dto =
            StepCompletionEventDtoD(
                contextId = event.contextId.toString(),
                planId = event.planId.toString(),
                stepId = event.stepId.toString(),
                stepName = event.stepName,
                stepStatus = event.stepStatus.name,
                timestamp = event.timestamp.toString(),
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    @EventListener
    fun onPlanStatusChange(event: PlanStatusChangeEvent) {
        val dto =
            PlanStatusChangeEventDtoD(
                contextId = event.contextId.toString(),
                planId = event.planId.toString(),
                planStatus = event.planStatusEnum.name,
                timestamp = event.timestamp.toString(),
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserTaskCreated(
        clientId: ClientId,
        task: TaskDocument,
        timestamp: String,
    ) {
        val dto =
            UserTaskCreatedEventDtoD(
                clientId = clientId.toString(),
                taskId = task.id.toString(),
                title = task.taskName,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserTaskCancelled(
        clientId: ClientId,
        task: TaskDocument,
        timestamp: String,
    ) {
        val dto =
            UserTaskCancelledEventDtoD(
                clientId = clientId.toString(),
                taskId = task.id.toString(),
                title = task.taskName,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserDialogRequest(
        dialogId: String,
        correlationId: String,
        clientId: ClientId,
        projectId: ProjectId?,
        question: String,
        proposedAnswer: String?,
        timestamp: String,
    ) {
        val dto =
            UserDialogRequestEventDtoD(
                dialogId = dialogId,
                correlationId = correlationId,
                clientId = clientId.toString(),
                projectId = projectId?.toString(),
                question = question,
                proposedAnswer = proposedAnswer,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserDialogClose(
        dialogId: String,
        correlationId: String,
        reason: String,
        timestamp: String,
    ) {
        val dto =
            UserDialogCloseEventDtoD(
                dialogId = dialogId,
                correlationId = correlationId,
                reason = reason,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }
}
