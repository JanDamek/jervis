package com.jervis.koog.tools

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.entity.TaskDocument
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared helper for A2A client management and request handling.
 *
 * Benefits:
 * - Single A2A client instance per baseUrl (cached in ConcurrentHashMap)
 * - Consistent request format across all coding tools
 * - Reduced code duplication
 * - Thread-safe client cache
 *
 * HttpClient lifecycle:
 * - Each HttpJSONRPCClientTransport creates its own HttpClient internally
 * - Clients are cached per baseUrl, so we create max 2 HttpClients (Aider + OpenHands)
 * - This is acceptable for current scale (low-frequency tool calls)
 * - If we need true singleton HttpClient, we'd need to pass it to HttpJSONRPCClientTransport constructor
 */
object A2AClientHelper {
    private val logger = KotlinLogging.logger {}
    private val clients = ConcurrentHashMap<String, A2AClient>()
    private val objectMapper = ObjectMapper()

    /**
     * Get or create A2A client for the specified base URL.
     * Clients are cached per baseUrl (thread-safe via ConcurrentHashMap).
     *
     * Note: Each baseUrl gets exactly one A2AClient instance, which internally
     * creates one HttpClient. For typical usage (2 services: Aider + OpenHands),
     * this results in 2 HttpClient instances total.
     */
    fun getOrCreate(baseUrl: String): A2AClient {
        return clients.getOrPut(baseUrl) {
            logger.info { "A2A_CLIENT_CREATE | baseUrl=$baseUrl" }
            val transport = HttpJSONRPCClientTransport(url = "$baseUrl/a2a")
            val agentCardResolver =
                UrlAgentCardResolver(
                    baseUrl = baseUrl,
                    path = "/.well-known/agent-card.json",
                )
            A2AClient(transport = transport, agentCardResolver = agentCardResolver)
        }
    }

    /**
     * Send coding request to A2A agent with consistent format.
     *
     * @param client A2A client instance
     * @param task Current task document (for correlation)
     * @param taskDescription High-level task description for the agent
     * @param targetFiles List of files to edit (empty for autonomous agents)
     * @param codingInstruction Detailed instructions for the agent
     * @param codingRules Soft rules/constraints for the agent
     * @return Response text from agent
     */
    suspend fun sendCodingRequest(
        client: A2AClient,
        task: TaskDocument,
        taskDescription: String,
        targetFiles: List<String>,
        codingInstruction: String,
        codingRules: String,
    ): String {
        logger.debug {
            "A2A_REQUEST | correlationId=${task.correlationId} | " +
                "clientId=${task.clientId} | projectId=${task.projectId} | " +
                "targetFiles=${targetFiles.size}"
        }

        // Build params map - omit projectId key if null (cleaner than empty string)
        val paramsMap =
            buildMap<String, Any?> {
                put("correlationId", task.correlationId)
                put("clientId", task.clientId.toString())
                if (task.projectId != null) {
                    put("projectId", task.projectId.toString())
                }
                put("taskDescription", taskDescription)
                put("targetFiles", targetFiles)
                put("codingInstruction", codingInstruction)
                put("codingRules", codingRules)
            }
        val paramsJson = objectMapper.writeValueAsString(paramsMap)

        val message =
            Message(
                messageId = UUID.randomUUID().toString(),
                role = Role.User,
                parts = listOf(TextPart(paramsJson)),
                contextId = task.correlationId,
            )

        val request = Request(data = MessageSendParams(message))

        return try {
            val response = client.sendMessage(request)

            when (val event = response.data) {
                is Message -> {
                    val text =
                        event.parts
                            .filterIsInstance<TextPart>()
                            .joinToString(" ") { it.text }

                    if (text.isBlank()) {
                        logger.warn { "A2A_RESPONSE_EMPTY | correlationId=${task.correlationId}" }
                        "ERROR: Agent returned empty response"
                    } else {
                        logger.debug { "A2A_RESPONSE | correlationId=${task.correlationId} | length=${text.length}" }
                        text
                    }
                }
                else -> {
                    logger.error { "A2A_RESPONSE_INVALID | correlationId=${task.correlationId} | type=${event::class.simpleName}" }
                    "ERROR: Unexpected response type: ${event::class.simpleName}"
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "A2A_REQUEST_FAILED | correlationId=${task.correlationId}" }
            "ERROR: A2A request failed: ${e.message}"
        }
    }
}
