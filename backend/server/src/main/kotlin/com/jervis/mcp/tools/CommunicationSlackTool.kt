package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class CommunicationSlackTool(
    override val promptRepository: PromptRepository,
) : McpTool<CommunicationSlackTool.CommunicationSlackParams> {
    private val logger = KotlinLogging.logger {}

    override val name = ToolTypeEnum.COMMUNICATION_SLACK_TOOL

    override val descriptionObject =
        CommunicationSlackParams(
            target = "#dev-team",
            message = "Deployment finished successfully at 15:42 UTC.",
        )

    @Serializable
    data class CommunicationSlackParams(
        val target: String,
        val message: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: CommunicationSlackParams,
    ): ToolResult = executeSlackOperation(request, plan)

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
