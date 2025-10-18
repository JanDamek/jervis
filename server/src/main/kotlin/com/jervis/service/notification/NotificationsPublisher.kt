package com.jervis.service.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import com.jervis.dto.events.PlanStatusChangeEventDto as PlanStatusChangeEventDtoD
import com.jervis.dto.events.StepCompletionEventDto as StepCompletionEventDtoD

@Component
class NotificationsPublisher {
    private val json = Json { encodeDefaults = true }
    private val sink: Sinks.Many<String> = Sinks.many().multicast().onBackpressureBuffer()

    fun stream(): Flux<String> = sink.asFlux()

    private fun emit(message: String) {
        sink.tryEmitNext(message)
    }

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
        emit(json.encodeToString(dto))
    }

    @EventListener
    fun onPlanStatusChange(event: PlanStatusChangeEvent) {
        val dto =
            PlanStatusChangeEventDtoD(
                contextId = event.contextId.toHexString(),
                planId = event.planId.toHexString(),
                planStatus = event.planStatus.name,
                timestamp = event.timestamp.toString(),
            )
        emit(json.encodeToString(dto))
    }
}
