package com.jervis.service.triage

import com.jervis.service.triage.dto.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Processes EventReceived domain events by running triage internally and emitting
 * TriageDecisionReady or TriageFailed.
 */
@Component
class EventReceivedProcessor(
    private val triageService: EventTriageService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @EventListener
    fun onEventReceived(event: EventReceived) {
        scope.launch {
            runCatching {
                triageService.decide(event.event, quick = true)
            }.onSuccess { decision: Decision ->
                eventPublisher.publishEvent(TriageDecisionReady(event.event, decision))
                logger.info {
                    "TRIAGE_DECISION_READY: intent=${decision.classification.intent}, actionable=${decision.classification.isActionable}"
                }
            }.onFailure { ex ->
                logger.error(ex) { "TRIAGE_DECISION_FAILED: ${ex.message}" }
                eventPublisher.publishEvent(TriageFailed(event.event, ex.message ?: "Unknown error"))
            }
        }
    }
}
