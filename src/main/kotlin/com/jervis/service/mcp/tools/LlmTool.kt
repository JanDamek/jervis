package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.LlmResponseWrapper
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.ToolResponseBuilder
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Service
class LlmTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.LLM

    @Serializable
    data class LlmParams(
        val systemPrompt: String? = null,
        val userPrompt: String = "",
        val contextFromSteps: Int = 0,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): LlmParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.LLM,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = LlmParams(),
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context)

        if (parsed.userPrompt.isBlank()) {
            return ToolResult.error("User prompt cannot be empty")
        }

        // Build additional context from contextFromSteps if specified in task
        val additionalContext =
            if (parsed.contextFromSteps > 0) {
                val recentSteps = plan.steps.takeLast(parsed.contextFromSteps)
                buildString {
                    appendLine("RECENT STEP RESULTS:")
                    recentSteps.forEachIndexed { index, step ->
                        append("${step.name}(${step.status})")
                        step.output?.let { append(": ${it.output}") }
                        if (index < recentSteps.size - 1) append(" | ")
                    }
                    appendLine()
                }
            } else {
                ""
            }

        // Combine external stepContext with internal additional context
        val combinedContext =
            buildString {
                if (stepContext.isNotEmpty()) {
                    append(stepContext)
                    if (additionalContext.isNotEmpty()) appendLine()
                }
                if (additionalContext.isNotEmpty()) {
                    append(additionalContext)
                }
            }.trim()

        // Execute the LLM call with combined context passed as stepContext parameter
        val llmResult =
            try {
                llmGateway.callLlm(
                    type = PromptTypeEnum.LLM,
                    userPrompt = parsed.userPrompt,
                    quick = context.quick,
                    mappingValue = parsed.systemPrompt?.let { mapOf("systemPrompt" to it) } ?: emptyMap(),
                    responseSchema = LlmResponseWrapper(),
                    stepContext = combinedContext
                )
            } catch (e: Exception) {
                return ToolResult.error("LLM call failed: ${e.message}")
            }

        val enhancedOutput = llmResult.response.trim().ifEmpty { "Empty LLM response" }
        val summary = if (parsed.systemPrompt != null) {
            "Processed prompt with custom system prompt"
        } else {
            "Processed user prompt"
        }

        return ToolResult.success(
            "LLM",
            summary,
            enhancedOutput
        )
    }
}
