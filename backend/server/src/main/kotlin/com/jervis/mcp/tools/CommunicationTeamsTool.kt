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
class CommunicationTeamsTool(
    override val promptRepository: PromptRepository,
) : McpTool<CommunicationTeamsTool.CommunicationTeamsParams> {
    private val logger = KotlinLogging.logger {}

    override val name = ToolTypeEnum.COMMUNICATION_TEAMS_TOOL

    override val descriptionObject =
        CommunicationTeamsParams(
            target = "general",
            message = "Daily standup starts in 5 minutes.",
        )

    @Serializable
    data class CommunicationTeamsParams(
        val target: String,
        val message: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: CommunicationTeamsParams,
    ): ToolResult = executeTeamsOperation(request, plan)

    private suspend fun executeTeamsOperation(
        params: CommunicationTeamsParams,
        plan: Plan,
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
