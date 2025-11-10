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
class CommunicationEmailTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.COMMUNICATION_EMAIL_TOOL

    @Serializable
    data class CommunicationEmailParams(
        val to: List<String> = listOf(""),
        val subject: String = "",
        val body: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String,
    ): CommunicationEmailParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.COMMUNICATION_EMAIL_TOOL,
                responseSchema = CommunicationEmailParams(),
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

        return executeEmailOperation(parsed, plan)
    }

    private suspend fun executeEmailOperation(
        params: CommunicationEmailParams,
        plan: Plan,
    ): ToolResult {
        // Note: This is a mock implementation. In production, you would integrate with an actual email service
        // such as SendGrid, Amazon SES, SMTP server, etc.

        val output =
            buildString {
                appendLine("📧 Email Communication")
                appendLine()
                appendLine("Recipients:")
                appendLine("  To: ${params.to.joinToString(", ")}")
                appendLine()
                appendLine("Subject: ${params.subject}")
                appendLine()
                appendLine("Message Body:")
                appendLine("---")
                appendLine(params.body)
                appendLine("---")
                appendLine()
                appendLine("✅ Email prepared successfully")
                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual email sending, integrate with:")
                appendLine("  - SMTP server configuration")
                appendLine("  - SendGrid API")
                appendLine("  - Amazon SES")
                appendLine("  - Microsoft Graph API")
                appendLine("  - Other email service providers")
            }

        return ToolResult.success("EMAIL", "Email prepared", output)
    }
}
