package com.jervis.service.notification

import com.jervis.domain.task.UserTask
import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.service.notification.domain.PlanStatusChangeEvent
import com.jervis.service.notification.domain.StepCompletionEvent
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import com.jervis.dto.events.PlanStatusChangeEventDto as PlanStatusChangeEventDtoD
import com.jervis.dto.events.StepCompletionEventDto as StepCompletionEventDtoD
import com.jervis.dto.events.UserTaskCreatedEventDto as UserTaskCreatedEventDtoD
import com.jervis.dto.events.UserTaskCancelledEventDto as UserTaskCancelledEventDtoD
import com.jervis.dto.events.AgentResponseEventDto as AgentResponseEventDtoD

@Component
class NotificationsPublisher(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json = Json { encodeDefaults = true }

    @EventListener
    fun onStepCompleted(event: StepCompletionEvent) {
        val dto =
            StepCompletionEventDtoD(
                contextId = event.contextId.toHexString(),
                planId = event.planId.toHexString(),
                stepId = event.stepId.toHexString(),
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
                contextId = event.contextId.toHexString(),
                planId = event.planId.toHexString(),
                planStatus = event.planStatusEnum.name,
                timestamp = event.timestamp.toString(),
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserTaskCreated(
        clientId: ObjectId,
        task: UserTask,
        timestamp: String,
    ) {
        val dto =
            UserTaskCreatedEventDtoD(
                clientId = clientId.toHexString(),
                taskId = task.id.toHexString(),
                title = task.title,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishUserTaskCancelled(
        clientId: ObjectId,
        task: UserTask,
        timestamp: String,
    ) {
        val dto =
            UserTaskCancelledEventDtoD(
                clientId = clientId.toHexString(),
                taskId = task.id.toHexString(),
                title = task.title,
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }

    fun publishAgentResponseCompleted(
        contextId: ObjectId,
        message: String,
        timestamp: String,
    ) {
        val dto =
            AgentResponseEventDtoD(
                wsSessionId = "BROADCAST",
                contextId = contextId.toHexString(),
                message = message,
                status = "COMPLETED",
                timestamp = timestamp,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelTypeEnum.NOTIFICATIONS)
    }
}
