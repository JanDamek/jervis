package com.jervis.aider.a2a

import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.TaskIdParams
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import com.jervis.aider.service.AiderService
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.TaskParams
import kotlinx.coroutines.Deferred
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Koog A2A AgentExecutor implementation for Aider service.
 *
 * Exposes AiderService as an A2A agent following Koog framework.
 */
@Component
class AiderAgentExecutor(
    private val aiderService: AiderService,
) : AgentExecutor {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
    ) {
        val message = context.params.message
        logger.info { "A2A_EXECUTE: contextId=${message.contextId}, messageId=${message.messageId}" }

        try {
            val taskDescription =
                message.parts
                    .filterIsInstance<TextPart>()
                    .joinToString(" ") { it.text }

            // Parse JSON from task description or metadata
            val paramsMap =
                try {
                    message.metadata?.let {
                        Json.decodeFromJsonElement<TaskParams>(it)
                    } ?: Json.decodeFromString<TaskParams>(taskDescription)
                } catch (e: SerializationException) {
                    logger.error(e) { "Failed to parse task description as JSON" }
                    eventProcessor.sendMessage(
                        Message(
                            messageId = UUID.randomUUID().toString(),
                            role = Role.Agent,
                            parts = listOf(TextPart("ERROR: Invalid request format")),
                            contextId = message.contextId,
                        ),
                    )
                    return
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse task description as JSON" }
                    eventProcessor.sendMessage(
                        Message(
                            messageId = UUID.randomUUID().toString(),
                            role = Role.Agent,
                            parts = listOf(TextPart("ERROR: Invalid request format")),
                            contextId = message.contextId,
                        ),
                    )
                    return
                }

            val codingRequest =
                CodingExecuteRequest(
                    correlationId = paramsMap.correlationId,
                    clientId = paramsMap.clientId,
                    projectId = paramsMap.projectId,
                    taskDescription = paramsMap.taskDescription,
                    targetFiles = paramsMap.targetFiles,
                    codingInstruction = paramsMap.codingInstruction,
                    codingRules = paramsMap.codingRules,
                )

            // Execute aider
            val response = aiderService.executeAider(codingRequest)

            // Send response message
            val resultText =
                buildString {
                    appendLine("success: ${response.success}")
                    appendLine("summary: ${response.summary}")
                }

            eventProcessor.sendMessage(
                Message(
                    messageId = UUID.randomUUID().toString(),
                    role = Role.Agent,
                    parts = listOf(TextPart(resultText)),
                    contextId = message.contextId,
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "A2A_EXECUTE_ERROR: ${e.message}" }
            eventProcessor.sendMessage(
                Message(
                    messageId = UUID.randomUUID().toString(),
                    role = Role.Agent,
                    parts = listOf(TextPart("ERROR: ${e.message}")),
                    contextId = message.contextId,
                ),
            )
        }
    }

    override suspend fun cancel(
        context: RequestContext<TaskIdParams>,
        eventProcessor: SessionEventProcessor,
        agentJob: Deferred<Unit>?,
    ) {
        logger.info { "A2A_CANCEL: taskId=${context.params.id}" }
        agentJob?.cancel()
    }
}
