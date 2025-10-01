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
class CommunicationSlackTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.COMMUNICATION_SLACK

    @Serializable
    data class CommunicationSlackParams(
        val target: String = "",
        val message: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): CommunicationSlackParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMMUNICATION_SLACK,
                responseSchema = CommunicationSlackParams(),
                quick = context.quick,
                mappingValue = mapOf("taskDescription" to taskDescription),
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

        return executeSlackOperation(parsed, context)
    }

    private suspend fun executeSlackOperation(
        params: CommunicationSlackParams,
        context: TaskContext,
    ): ToolResult {
        val output =
            buildString {
                appendLine("💬 Slack Message")
                appendLine()
                appendLine("Target: ${params.target}")
                appendLine()
                appendLine("Message Content:")
                appendLine("---")
                appendLine(params.message)
                appendLine("---")
                appendLine()
                appendLine("✅ Message prepared for Slack")
                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual Slack messaging, integrate with Slack Web API")
            }

        return ToolResult.success("SLACK", "Message prepared", output)
    }
}
