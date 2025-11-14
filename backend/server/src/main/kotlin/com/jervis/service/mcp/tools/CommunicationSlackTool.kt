package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
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

    override val name: PromptTypeEnum = PromptTypeEnum.COMMUNICATION_SLACK_TOOL

    @Serializable
    data class CommunicationSlackParams(
        val target: String = "",
        val message: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String,
    ): CommunicationSlackParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMMUNICATION_SLACK_TOOL,
                responseSchema = CommunicationSlackParams(),
                correlationId = plan.correlationId,
                quick = plan.quick,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                backgroundMode = plan.backgroundMode,
            )

        return llmResponse.result
    }

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, plan, stepContext)

        return executeSlackOperation(parsed, plan)
    }

    private suspend fun executeSlackOperation(
        params: CommunicationSlackParams,
        plan: Plan,
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
