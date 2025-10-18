package com.jervis.service.triage

import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.triage.dto.Decision
import com.jervis.service.triage.dto.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Bridge listener that consumes TriageDecisionReady events and routes actionable
 * notifications (e.g., bug emails) into the planning orchestrator.
 *
 * Non-actionable items are only logged here; indexing/storage is handled elsewhere.
 */
@Component
class AgentEventBridgeListener(
    private val agentOrchestratorService: AgentOrchestratorService,
    private val contextThreadLinkService: ContextThreadLinkService,
    private val taskContextService: com.jervis.service.agent.context.TaskContextService,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default)

    @EventListener
    fun onTriageDecisionReady(event: TriageDecisionReady) {
        val decision: Decision = event.decision
        val envelope: EventEnvelope = event.event

        logger.info {
            "EVENT_BRIDGE: decision threadKey='${decision.routing.threadKey}', intent='${decision.classification.intent}', actionable=${decision.classification.isActionable}, needRag=${decision.rag.needRag}, action=${decision.action.mode}"
        }

        if (!decision.classification.isActionable) return

        val intent = decision.classification.intent.uppercase()
        val actionable = intent in setOf("BUG", "REQUEST", "ALERT", "OTHER")
        if (!actionable) return

        val clientIdStr = decision.routing.clientId
        val projectIdStr = decision.routing.projectId
        if (clientIdStr.isNullOrBlank() || projectIdStr.isNullOrBlank()) {
            logger.warn { "EVENT_BRIDGE_SKIP: Missing clientId/projectId for actionable event; cannot start plan." }
            return
        }

        val clientId =
            runCatching { ObjectId(clientIdStr) }.getOrElse {
                logger.warn { "EVENT_BRIDGE_SKIP: Invalid clientId='$clientIdStr'" }
                return
            }
        val projectId =
            runCatching { ObjectId(projectIdStr) }.getOrElse {
                logger.warn { "EVENT_BRIDGE_SKIP: Invalid projectId='$projectIdStr'" }
                return
            }

        // Build a concise user-like question to seed planning
        val synthesizedText = synthesizePromptText(intent, envelope)

        scope.launch {
            try {
                // Try to reuse existing TaskContext by threadKey, otherwise create new and link
                val threadKey = decision.routing.threadKey
                val existingContextId = contextThreadLinkService.findContextId(threadKey)
                val contextId =
                    existingContextId ?: run {
                        val contextName = buildContextName(intent, envelope)
                        val created =
                            taskContextService.create(clientId, projectId, quick = false, contextName = contextName)
                        contextThreadLinkService.link(threadKey, created.id)
                        created.id
                    }

                agentOrchestratorService.handle(
                    text = synthesizedText,
                    clientId = clientId,
                    projectId = projectId,
                    quick = false,
                    existingContextId = contextId,
                )
            } catch (e: Exception) {
                logger.error(e) { "EVENT_BRIDGE_ERROR: Failed to start orchestrator for triaged event: ${e.message}" }
            }
        }
    }

    private fun synthesizePromptText(
        intent: String,
        envelope: EventEnvelope,
    ): String {
        val type = envelope.artifact.type
        val subject = envelope.artifact.metadata["subject"]?.takeIf { it.isNotBlank() }
        val from = envelope.artifact.metadata["from"]?.takeIf { it.isNotBlank() }
        val snippet =
            envelope.artifact.text
                ?.trim()
                ?.take(300)
        val source = "${envelope.source.channel.name}:${envelope.source.provider ?: ""}"

        return buildString {
            append("Handle ")
            append(intent.lowercase())
            append(" from ")
            append(type)
            append(" (")
            append(source)
            append(")")
            if (from != null) append(", sender: ").append(from)
            if (subject != null) append(", subject: ").append(subject)
            if (!snippet.isNullOrBlank()) {
                append(". Content: \"")
                append(snippet.replace("\n", " "))
                append("\"")
            }
        }
    }

    private fun buildContextName(
        intent: String,
        envelope: EventEnvelope,
    ): String {
        val subject = envelope.artifact.metadata["subject"]?.takeIf { it.isNotBlank() }
        val base =
            subject ?: envelope.artifact.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.take(60)
                ?: "Actionable ${intent.lowercase()}"
        return when (intent) {
            "BUG" -> "Bug: $base"
            "REQUEST" -> "Request: $base"
            "ALERT" -> "Alert: $base"
            else -> base
        }
    }
}
