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
 * AiderCodingTool – local surgical code edits using Aider (CLI) on the project workspace.
 *
 * Uses Koog A2A Client to communicate with service-aider.
 *
 * Use for:
 * - Small, targeted changes in existing files (bugfixes, refactors)
 * - Fast iterative edits when you already know affected files
 *
 * Do NOT use for:
 * - Spinning up/running apps, heavy debugging across services, installing deps
 *   → use OpenHandsCodingTool for that (isolated K8s environment)
 */
@LLMDescription(
    "Local surgical code edits via Aider on the project's git workspace. Ideal for small fixes/refactors in specific files.",
)
class AiderCodingTool(
    private val task: TaskDocument,
    private val aiderBaseUrl: String = "http://localhost:8081"
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val objectMapper = ObjectMapper()
    }

    private val a2aClient: A2AClient by lazy {
        val transport = HttpJSONRPCClientTransport(url = "$aiderBaseUrl/a2a")
        val agentCardResolver = UrlAgentCardResolver(
            baseUrl = aiderBaseUrl,
            path = "/.well-known/agent-card.json"
        )
        A2AClient(transport = transport, agentCardResolver = agentCardResolver)
    }

    @Tool
    @LLMDescription(
        "Run Aider with a clear task description and list of target files (relative paths under the project's git directory). " +
            "By default uses fast local Qwen model. For complex/critical tasks, set model='paid' to use more powerful paid API.",
    )
    suspend fun runAiderCoding(
        @LLMDescription("Clear task description for the programmer. Be specific about what to change and why.")
        taskDescription: String,
        @LLMDescription("List of relative file paths to edit (from GraphDB or repository context)")
        targetFiles: List<String>,
    ): String {
        logger.info { "AIDER_TOOL_CALL: files=$targetFiles" }

        return try {
            // Build request params as JSON
            val paramsMap = mapOf(
                "correlationId" to task.correlationId,
                "clientId" to task.clientId.toString(),
                "projectId" to task.projectId.toString(),
                "taskDescription" to taskDescription,
                "targetFiles" to targetFiles,
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
                        appendLine("AIDER_RESULT:")
                        appendLine(text)
                    }
                }
                else -> "AIDER_RESULT: Unexpected response type"
            }
        } catch (e: Exception) {
            logger.error(e) { "AIDER_A2A_ERROR: ${e.message}" }
            "ERROR: Aider A2A call failed: ${e.message}"
        }
    }
}
