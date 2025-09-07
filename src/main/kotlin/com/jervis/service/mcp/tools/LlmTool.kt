package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.PromptType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class LlmTool(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) : McpTool {
    override val name: String = "llm"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.LLM)

    @Serializable
    data class LlmParams(
        val systemPrompt: String? = null,
        val userPrompt: String,
        val modelType: String = "INTERNAL",
        val contextFromSteps: Int = 0,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): LlmParams {
        val systemPrompt = promptRepository.getMcpToolSystemPrompt(PromptType.LLM_SYSTEM)

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = ModelType.INTERNAL,
                    systemPrompt = systemPrompt,
                    userPrompt = taskDescription,
                    outputLanguage = "en",
                    quick = context.quick,
                )

            val cleanedResponse =
                llmResponse.answer
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            Json.decodeFromString<LlmParams>(cleanedResponse)
        } catch (e: Exception) {
            // Enhanced fallback logic based on keywords
            val modelType =
                when {
                    taskDescription.contains("translat", ignoreCase = true) ||
                        taskDescription.contains("language", ignoreCase = true) -> "TRANSLATION"

                    taskDescription.contains("plan", ignoreCase = true) ||
                        taskDescription.contains("strateg", ignoreCase = true) ||
                        taskDescription.contains("decision", ignoreCase = true) -> "PLANNER"

                    taskDescription.contains("chat", ignoreCase = true) -> "CHAT_INTERNAL"
                    else -> "INTERNAL"
                }

            val contextFromSteps =
                when {
                    taskDescription.contains("previous", ignoreCase = true) ||
                        taskDescription.contains("context", ignoreCase = true) ||
                        taskDescription.contains("from steps", ignoreCase = true) -> {
                        // Try to extract number if mentioned
                        val numberRegex = Regex("\\b(\\d+)\\s*steps?\\b", RegexOption.IGNORE_CASE)
                        numberRegex
                            .find(taskDescription)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?.coerceIn(1, 5) ?: 2
                    }

                    else -> 0
                }

            val systemPrompt =
                when (modelType) {
                    "TRANSLATION" -> "You are a translation assistant. Provide accurate translations and language analysis."
                    "PLANNER" -> "You are a strategic planner. Analyze situations and provide actionable recommendations."
                    "CHAT_INTERNAL" -> "You are a helpful chat assistant. Provide conversational and friendly responses."
                    else -> "You are a helpful AI assistant. Analyze the request and provide a clear, detailed response."
                }

            LlmParams(
                systemPrompt = systemPrompt,
                userPrompt = taskDescription,
                modelType = modelType,
                contextFromSteps = contextFromSteps,
            )
        }
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context)

        if (parsed.userPrompt.isBlank()) {
            return ToolResult.error("User prompt cannot be empty")
        }

        // Validate and map model type
        val modelType =
            try {
                ModelType.valueOf(parsed.modelType.uppercase())
            } catch (e: IllegalArgumentException) {
                return ToolResult.error(
                    "Invalid model type: ${parsed.modelType}. Available types: INTERNAL, TRANSLATION, PLANNER, CHAT_INTERNAL, CHAT_EXTERNAL",
                )
            }

        // Collect context from previous steps if requested
        val stepContext =
            if (parsed.contextFromSteps > 0) {
                val recentSteps = plan.steps.takeLast(parsed.contextFromSteps)
                buildString {
                    appendLine("=== Context from Previous ${recentSteps.size} Steps ===")
                    recentSteps.forEachIndexed { index, step ->
                        appendLine("Step ${index + 1}: ${step.name}")
                        appendLine("Status: ${step.status}")
                        step.output?.let { toolResult ->
                            appendLine("Result: ${toolResult.render()}")
                            appendLine("Full Output: ${toolResult.output}")
                        }
                        appendLine("---")
                    }
                    appendLine("=== End Context ===")
                    appendLine()
                }
            } else {
                ""
            }

        // Build the complete user prompt with context
        val completeUserPrompt =
            buildString {
                if (stepContext.isNotEmpty()) {
                    append(stepContext)
                }
                append(parsed.userPrompt)
            }

        // Execute the main LLM call
        val llmResult =
            try {
                llmGateway.callLlm(
                    type = modelType,
                    systemPrompt =
                        parsed.systemPrompt
                            ?: "You are a helpful AI assistant. Provide clear, accurate, and actionable responses.",
                    userPrompt = completeUserPrompt,
                    outputLanguage = "en",
                    quick = context.quick,
                )
            } catch (e: Exception) {
                return ToolResult.error("LLM call failed: ${e.message}")
            }

        val enhancedOutput =
            buildString {
                appendLine("🤖 LLM Processing Results")
                appendLine("Model Type: $modelType")
                if (parsed.contextFromSteps > 0) {
                    appendLine("Context Steps: ${parsed.contextFromSteps}")
                }
                appendLine("Model Used: ${llmResult.model}")
                appendLine()
                appendLine("📝 LLM Response:")
                appendLine("```")
                appendLine(llmResult.answer.trim())
                appendLine("```")

                if (llmResult.answer.trim().isEmpty()) {
                    appendLine()
                    appendLine("⚠️ Warning: LLM returned empty response")
                }
            }

        return ToolResult.ok(enhancedOutput)
    }
}
