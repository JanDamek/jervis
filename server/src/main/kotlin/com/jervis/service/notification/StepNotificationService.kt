package com.jervis.service.notification

import com.jervis.domain.plan.PlanStatusEnum
import com.jervis.domain.plan.PlanStep
import com.jervis.service.notification.domain.PlanStatusChangeEvent
import com.jervis.service.notification.domain.StepCompletionEvent
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StepNotificationService(
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun notifyStepCompleted(
        contextId: ObjectId,
        planId: ObjectId,
        step: PlanStep,
    ) {
        val event =
            StepCompletionEvent(
                contextId = contextId,
                planId = planId,
                stepId = step.id,
                stepName = step.stepToolName.name,
                stepStatus = step.status,
                timestamp = Instant.now(),
            )
        eventPublisher.publishEvent(event)
    }

    fun notifyPlanStatusChanged(
        contextId: ObjectId,
        planId: ObjectId,
        planStatusEnum: PlanStatusEnum,
    ) {
        val event =
            PlanStatusChangeEvent(
                contextId = contextId,
                planId = planId,
                planStatusEnum = planStatusEnum,
                timestamp = Instant.now(),
            )
        eventPublisher.publishEvent(event)
    }
}
