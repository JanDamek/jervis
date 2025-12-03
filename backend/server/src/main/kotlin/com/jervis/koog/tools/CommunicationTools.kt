package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import mu.KotlinLogging

/**
 * Communication tools for sending emails, Slack, and Teams messages.
 * Native Koog implementation - no MCP dependencies.
 *
 * NOTE: These are currently mock implementations. To enable actual sending:
 * - Email: Integrate with SMTP, SendGrid, Amazon SES, or Microsoft Graph API
 * - Slack: Integrate with Slack Web API
 * - Teams: Integrate with Microsoft Graph API
 */
@LLMDescription("Communication tools: send emails, Slack messages, Teams messages")
class CommunicationTools(
    private val plan: Plan,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Send email to specified recipients.
NOTE: This is a mock implementation. To enable actual email sending, integrate with:
- SMTP server configuration
- SendGrid API
- Amazon SES
- Microsoft Graph API""")
    fun sendEmail(
        @LLMDescription("Recipient email addresses (comma-separated)")
        to: String,

        @LLMDescription("Email subject")
        subject: String,

        @LLMDescription("Email body content")
        body: String,
    ): String {
        logger.info { "EMAIL: to='$to', subject='$subject'" }

        val recipients = to.split(",").map { it.trim() }

        return buildString {
            appendLine("📧 Email Communication")
            appendLine()
            appendLine("Recipients:")
            appendLine("  To: ${recipients.joinToString(", ")}")
            appendLine()
            appendLine("Subject: $subject")
            appendLine()
            appendLine("Message Body:")
            appendLine("---")
            appendLine(body)
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
    }

    @Tool
    @LLMDescription("""Send message to Slack channel or user.
NOTE: This is a mock implementation. To enable actual Slack messaging, integrate with Slack Web API.""")
    fun sendSlack(
        @LLMDescription("Target channel (#channel-name) or user (@username)")
        target: String,

        @LLMDescription("Message content")
        message: String,
    ): String {
        logger.info { "SLACK: target='$target'" }

        return buildString {
            appendLine("💬 Slack Message")
            appendLine()
            appendLine("Target: $target")
            appendLine()
            appendLine("Message Content:")
            appendLine("---")
            appendLine(message)
            appendLine("---")
            appendLine()
            appendLine("✅ Message prepared for Slack")
            appendLine()
            appendLine("⚠️ Note: This is a mock implementation. To enable actual Slack messaging, integrate with Slack Web API")
        }
    }

    @Tool
    @LLMDescription("""Send message to Microsoft Teams channel or user.
NOTE: This is a mock implementation. To enable actual Teams messaging, integrate with Microsoft Graph API.""")
    fun sendTeams(
        @LLMDescription("Target channel or user")
        target: String,

        @LLMDescription("Message content")
        message: String,
    ): String {
        logger.info { "TEAMS: target='$target'" }

        return buildString {
            appendLine("🔷 Microsoft Teams Message")
            appendLine()
            appendLine("Target: $target")
            appendLine()
            appendLine("Message Content:")
            appendLine("---")
            appendLine(message)
            appendLine("---")
            appendLine()
            appendLine("✅ Message prepared for Teams")
            appendLine()
            appendLine("⚠️ Note: This is a mock implementation. To enable actual Teams messaging, integrate with Microsoft Graph API")
        }
    }
}
