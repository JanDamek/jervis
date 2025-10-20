package com.jervis.service.notification

import com.jervis.service.notification.domain.PlanStatusChangeEvent
import com.jervis.service.notification.domain.StepCompletionEvent
import com.jervis.service.websocket.WebSocketChannelType
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import com.jervis.dto.events.PlanStatusChangeEventDto as PlanStatusChangeEventDtoD
import com.jervis.dto.events.StepCompletionEventDto as StepCompletionEventDtoD

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
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelType.NOTIFICATIONS)
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
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelType.NOTIFICATIONS)
    }
}
