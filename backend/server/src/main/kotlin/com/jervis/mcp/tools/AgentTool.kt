package com.jervis.mcp.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.koog.KoogWorkflowAgent
import com.jervis.mcp.McpTool
import com.jervis.mcp.McpToolRegistry
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import com.jervis.graphdb.GraphDBService
import org.springframework.context.annotation.Lazy

/**
 * AgentTool – bridges JERVIS Agent Orchestrator to JetBrains Koog complex workflow agent.
 * Uses official Koog AIAgent (v0.5.3) with a strategy and the ToolRegistry built from existing MCP tools.
 */
@Service
class AgentTool(
    override val promptRepository: PromptRepository,
    @Lazy private val mcpRegistry: McpToolRegistry,
    private val graphDBService: GraphDBService,
    private val koogWorkflowAgent: KoogWorkflowAgent,
) : McpTool<AgentTool.Description> {

    companion object { private val logger = KotlinLogging.logger {} }

    override val name = ToolTypeEnum.KOOG_AGENT_TOOL

    @Serializable
    data class Description(
        val role: String,
        val goals: List<String>,
        val context: Map<String, String> = emptyMap(),
        val memoryHints: List<String> = emptyList(),
    )

    override val descriptionObject =
        Description(
            role = "Agent specialization (graph-builder, rag-enricher, triage)",
            goals = listOf("Short list of objectives to achieve"),
            context = mapOf("projectId" to "<ObjectId as hex string>"),
            memoryHints = emptyList(),
        )

    private val mapper = jacksonObjectMapper()

    override suspend fun execute(plan: Plan, request: Description): ToolResult {
        // Compose user input for Koog based on role + goals (planner provides these)
        val inputText = buildString {
            appendLine(request.role)
            request.goals.forEach { appendLine("- $it") }
        }.trim()

        // Merge auxiliary metadata into a single envelope (for logging/debug only)
        val metadata = mutableMapOf<String, String>()
        metadata["correlationId"] = plan.correlationId
        metadata["backgroundMode"] = plan.backgroundMode.toString()
        plan.projectDocument?.id?.toHexString()?.let { metadata["projectId"] = it }
        metadata.putAll(request.context)

        return try {
            logger.info { "KOOG_AGENT_TOOL: Starting Koog workflow for role='${request.role}' goals=${request.goals.size}" }

            val output = koogWorkflowAgent.run(plan, mcpRegistry, graphDBService, inputText)

            val envelopeJson = mapper.writeValueAsString(
                mapOf(
                    "input" to inputText,
                    "metadata" to metadata,
                )
            )

            ToolResult.success(
                toolName = name.name,
                summary = "Koog agent finished (${request.role})",
                content = buildString {
                    appendLine("INPUT_ENVELOPE:")
                    appendLine(envelopeJson)
                    appendLine()
                    appendLine("OUTPUT:")
                    appendLine(output)
                },
            )
        } catch (e: Exception) {
            logger.warn(e) { "KOOG_AGENT_TOOL failed" }
            ToolResult.error(
                output = "Koog agent failed: ${e.message}",
                message = e.message,
            )
        }
    }
}
