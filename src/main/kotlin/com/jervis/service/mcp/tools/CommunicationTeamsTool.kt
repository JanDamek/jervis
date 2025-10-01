package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class CommunicationTeamsTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.COMMUNICATION_TEAMS

    @Serializable
    data class CommunicationTeamsParams(
        val target: String = "",
        val message: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): CommunicationTeamsParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMMUNICATION_TEAMS,
                mappingValue = mapOf("taskDescription" to taskDescription),
                quick = context.quick,
                responseSchema = CommunicationTeamsParams(),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeTeamsOperation(parsed, context)
    }

    private suspend fun executeTeamsOperation(
        params: CommunicationTeamsParams,
        context: TaskContext,
    ): ToolResult {
        val output =
            buildString {
                appendLine("🔷 Microsoft Teams Message")
                appendLine()
                appendLine("Target: ${params.target}")
                appendLine()
                appendLine("Message Content:")
                appendLine("---")
                appendLine(params.message)
                appendLine("---")
                appendLine()
                appendLine("✅ Message prepared for Teams")
                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual Teams messaging, integrate with Microsoft Graph API")
            }

        return ToolResult.success("TEAMS", "Message prepared", output)
    }
}
