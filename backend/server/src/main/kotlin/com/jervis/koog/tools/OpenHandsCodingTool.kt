package com.jervis.koog.tools

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.transport.Request
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.entity.TaskDocument
import mu.KotlinLogging
import java.util.*

/**
 * OpenHandsCodingTool – heavy-weight autonomous coding in isolated K8s sandbox (Server 3).
 *
 * Uses Koog A2A Client to communicate with service-coding-engine.
 *
 * Use for:
 * - New projects or large-scale refactors
 * - Running and debugging applications, installing dependencies
 * - Multi-step workflows requiring long runtime and tools
 *
 * Not ideal for:
 * - Small, surgical edits in a couple of files → use AiderCodingTool instead
 */
@LLMDescription(
    "Delegate complex coding tasks to OpenHands in an isolated K8s environment. Ideal for running apps, heavy debugging, or large changes.",
)
class OpenHandsCodingTool(
    private val task: TaskDocument,
    private val openHandsBaseUrl: String = "http://localhost:8082"
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val objectMapper = ObjectMapper()
    }

    private val a2aClient: A2AClient by lazy {
        val transport = HttpJSONRPCClientTransport(url = "$openHandsBaseUrl/a2a")
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = openHandsBaseUrl,
            path = "/.well-known/agent-card.json"
        )
        A2AClient(transport = transport, agentCardResolver = agentCardResolver)
    }

    @Tool
    @LLMDescription(
        "Submit a complex job to OpenHands. Provide a detailed task spec. Optionally include repoUrl to clone. " +
            "By default uses fast local Qwen model. For complex/critical tasks.",
    )
    suspend fun delegateToOpenHands(
        @LLMDescription("Detailed task specification for OpenHands (what to build/run/debug and expected outcome)")
        taskSpec: String,
    ): String {
        logger.info { "OPENHANDS_TOOL_SUBMIT: correlationId=${task.correlationId}" }

        return try {
            // Build request params as JSON
            val paramsMap = mapOf(
                "correlationId" to task.correlationId,
                "clientId" to task.clientId.toString(),
                "projectId" to task.projectId.toString(),
                "taskDescription" to taskSpec,
                "targetFiles" to emptyList<String>(),
                "codingInstruction" to " ",
                "codingRules" to " "
            )
            val paramsJson = objectMapper.writeValueAsString(paramsMap)

            // Create A2A message
            val message = Message(
                messageId = UUID.randomUUID().toString(),
                role = Role.User,
                parts = listOf(TextPart(paramsJson)),
                contextId = task.correlationId
            )

            val request = Request(data = MessageSendParams(message))

            // Send message and get response
            val response = a2aClient.sendMessage(request)

            when (val event = response.data) {
                is Message -> {
                    val text = event.parts
                        .filterIsInstance<TextPart>()
                        .joinToString(" ") { it.text }
                    buildString {
                        appendLine("OPENHANDS_RESULT:")
                        appendLine(text)
                    }
                }
                else -> "OPENHANDS_RESULT: Unexpected response type"
            }
        } catch (e: Exception) {
            logger.error(e) { "OPENHANDS_A2A_ERROR: ${e.message}" }
            "ERROR: OpenHands A2A call failed: ${e.message}"
        }
    }
}
