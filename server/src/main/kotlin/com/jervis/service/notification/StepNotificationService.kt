package com.jervis.service.notification

import com.jervis.domain.plan.PlanStep
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
        planStatus: com.jervis.domain.plan.PlanStatus,
    ) {
        val event =
            PlanStatusChangeEvent(
                contextId = contextId,
                planId = planId,
                planStatus = planStatus,
                timestamp = Instant.now(),
            )
        eventPublisher.publishEvent(event)
    }
}

data class StepCompletionEvent(
    val contextId: ObjectId,
    val planId: ObjectId,
    val stepId: ObjectId,
    val stepName: String,
    val stepStatus: com.jervis.domain.plan.StepStatus,
    val timestamp: Instant,
)

data class PlanStatusChangeEvent(
    val contextId: ObjectId,
    val planId: ObjectId,
    val planStatus: com.jervis.domain.plan.PlanStatus,
    val timestamp: Instant,
)
