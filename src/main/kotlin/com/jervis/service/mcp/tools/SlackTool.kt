package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpJson
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class SlackTool(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: String = "slack"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.SLACK)

    @Serializable
    data class SlackParams(
        val action: String,
        val target_type: String,
        val target: String,
        val message: String,
        val thread_ts: String? = null,
        val mentions: List<String> = emptyList(),
        val blocks: JsonArray = JsonArray(emptyList()),
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): SlackParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.SLACK)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.SLACK,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = SlackParams("", "", "", "", null, emptyList(), JsonArray(emptyList())),
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val parsed =
            try {
                parseTaskDescription(taskDescription, context)
            } catch (e: Exception) {
                return ToolResult.error("Invalid Slack parameters: ${e.message}", "Slack parameter parsing failed")
            }

        if (parsed.action.isBlank()) {
            return ToolResult.error("Slack action cannot be empty")
        }

        if (parsed.target_type.isBlank()) {
            return ToolResult.error("Slack target type cannot be empty")
        }

        if (parsed.target.isBlank()) {
            return ToolResult.error("Slack target cannot be empty")
        }

        if (parsed.message.isBlank()) {
            return ToolResult.error("Slack message cannot be empty")
        }

        // Validate target_type
        if (parsed.target_type !in listOf("channel", "user", "dm")) {
            return ToolResult.error("Invalid target type. Must be: channel, user, or dm")
        }

        val result =
            withContext(Dispatchers.IO) {
                try {
                    executeSlackOperation(parsed)
                } catch (e: Exception) {
                    logger.error(e) { "Slack operation failed: ${parsed.action}" }
                    ToolResult.error("Slack operation failed: ${e.message}")
                }
            }

        return result
    }

    private suspend fun executeSlackOperation(params: SlackParams): ToolResult {
        // Note: This is a mock implementation. In production, you would integrate with Slack Web API
        // for actual Slack messaging functionality.

        val output =
            buildString {
                appendLine("💬 Slack Operation: ${params.action}")
                appendLine()
                appendLine("Target Details:")
                appendLine("  Type: ${params.target_type}")
                appendLine("  Target: ${params.target}")
                if (params.thread_ts != null) {
                    appendLine("  Thread Timestamp: ${params.thread_ts}")
                }
                appendLine()
                if (params.mentions.isNotEmpty()) {
                    appendLine("Mentions: ${params.mentions.joinToString(", ") { "@$it" }}")
                    appendLine()
                }
                appendLine("Message Content:")
                appendLine("---")
                appendLine(params.message)
                appendLine("---")
                appendLine()

                if (params.blocks.size > 0) {
                    appendLine("Rich Formatting:")
                    appendLine("  Block Kit elements: ${params.blocks.size} blocks")
                    appendLine()
                }

                when (params.target_type) {
                    "channel" -> {
                        val channelName = if (params.target.startsWith("#")) params.target else "#${params.target}"
                        appendLine("✅ Message prepared for Slack channel: $channelName")
                        appendLine("📢 Channel members will receive notifications based on their settings")
                    }

                    "user" -> {
                        appendLine("✅ Direct message prepared for user: @${params.target}")
                        appendLine("👤 User will receive a personal notification")
                    }

                    "dm" -> {
                        appendLine("✅ Direct message prepared for: ${params.target}")
                        appendLine("💬 Participants will receive notifications")
                    }
                }

                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual Slack messaging, integrate with:")
                appendLine("  - Slack Web API")
                appendLine("  - Slack Bot Token authentication")
                appendLine("  - Proper Slack app configuration")
                appendLine("  - OAuth 2.0 or Bot User OAuth Access Token")
                appendLine("  - Webhook URLs for incoming messages")
                appendLine("  - Block Kit for rich message formatting")
            }

        return ToolResult.ok(output)
    }
}
