package com.jervis.coding.a2a

import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.TaskIdParams
import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.coding.service.CodingEngineService
import com.jervis.common.dto.CodingExecuteRequest
import kotlinx.coroutines.Deferred
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.*

/**
 * Koog A2A AgentExecutor implementation for OpenHands service.
 *
 * Exposes CodingEngineService as an A2A agent following Koog framework.
 */
@Component
class OpenHandsAgentExecutor(
    private val codingEngineService: CodingEngineService,
    private val objectMapper: ObjectMapper
) : AgentExecutor {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val message = context.params.message
        logger.info { "A2A_EXECUTE: contextId=${message.contextId}, messageId=${message.messageId}" }

        try {
            // Extract task description from message
            val taskDescription = message.parts
                .filterIsInstance<TextPart>()
                .joinToString(" ") { it.text }

            // Parse JSON from task description (expected format from tools)
            val paramsMap = try {
                objectMapper.readValue(taskDescription, Map::class.java) as Map<String, Any?>
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse task description as JSON" }
                eventProcessor.sendMessage(
                    Message(
                        messageId = UUID.randomUUID().toString(),
                        role = Role.Agent,
                        parts = listOf(TextPart("ERROR: Invalid request format")),
                        contextId = message.contextId
                    )
                )
                return
            }

            val codingRequest = CodingExecuteRequest(
                correlationId = paramsMap["correlationId"]?.toString() ?: "",
                clientId = paramsMap["clientId"]?.toString() ?: "",
                projectId = paramsMap["projectId"]?.toString() ?: "",
                taskDescription = paramsMap["taskDescription"]?.toString() ?: "",
                targetFiles = (paramsMap["targetFiles"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                codingInstruction = paramsMap["codingInstruction"]?.toString() ?: "",
                codingRules = paramsMap["codingRules"]?.toString() ?: ""
            )

            // Execute OpenHands
            val response = codingEngineService.executeOpenHands(codingRequest)

            // Send response message
            val resultText = buildString {
                appendLine("success: ${response.success}")
                appendLine("summary: ${response.summary}")
            }

            eventProcessor.sendMessage(
                Message(
                    messageId = UUID.randomUUID().toString(),
                    role = Role.Agent,
                    parts = listOf(TextPart(resultText)),
                    contextId = message.contextId
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "A2A_EXECUTE_ERROR: ${e.message}" }
            eventProcessor.sendMessage(
                Message(
                    messageId = UUID.randomUUID().toString(),
                    role = Role.Agent,
                    parts = listOf(TextPart("ERROR: ${e.message}")),
                    contextId = message.contextId
                )
            )
        }
    }

    override suspend fun cancel(
        context: RequestContext<TaskIdParams>,
        eventProcessor: SessionEventProcessor,
        agentJob: Deferred<Unit>?
    ) {
        logger.info { "A2A_CANCEL: taskId=${context.params.id}" }
        agentJob?.cancel()
    }
}
