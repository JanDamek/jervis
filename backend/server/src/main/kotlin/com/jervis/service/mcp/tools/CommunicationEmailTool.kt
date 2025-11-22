package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class CommunicationEmailTool(
    override val promptRepository: PromptRepository,
) : McpTool<CommunicationEmailTool.CommunicationEmailParams> {
    private val logger = KotlinLogging.logger {}

    override val name = ToolTypeEnum.COMMUNICATION_EMAIL_TOOL

    override val descriptionObject =
        CommunicationEmailParams(
            to = listOf("user@example.com"),
            subject = "Subject of the email",
            body = "Body of the email message",
        )

    @Serializable
    data class CommunicationEmailParams(
        val to: List<String>,
        val subject: String,
        val body: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: CommunicationEmailParams,
    ): ToolResult = executeEmailOperation(request, plan)

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
