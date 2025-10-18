package com.jervis.service.triage

import com.jervis.service.triage.dto.Decision
import com.jervis.service.triage.dto.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Accepts normalized EventEnvelope objects from listeners and processes them asynchronously.
 * - Immediately returns control to the caller (listeners do not need a response)
 * - Performs first-call triage using EventTriageService
 * - Publishes a domain event with the Decision for decoupled consumers (e.g., AgentOrchestratorService)
 */
@Service
class EventIngestionService(
    private val triageService: EventTriageService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Accepts an EventEnvelope and starts background triage.
     * Publishes EventReceived immediately and later emits TriageDecisionReady or TriageFailed.
     */
    fun ingest(event: EventEnvelope) {
        eventPublisher.publishEvent(EventReceived(event))
    }
}

/** Event emitted when a raw EventEnvelope is received. */
data class EventReceived(
    val event: EventEnvelope,
)

/** Event emitted after successful triage with the Decision result. */
data class TriageDecisionReady(
    val event: EventEnvelope,
    val decision: Decision,
)

/** Event emitted when triage fails. */
data class TriageFailed(
    val event: EventEnvelope,
    val reason: String,
)
