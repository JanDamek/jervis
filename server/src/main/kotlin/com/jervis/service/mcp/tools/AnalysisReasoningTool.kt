package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import org.springframework.stereotype.Service

@Service
class AnalysisReasoningTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.ANALYSIS_REASONING_TOOL

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult = executeAnalysisOperation(taskDescription, context, stepContext)

    private suspend fun executeAnalysisOperation(
        params: String,
        context: TaskContext,
        stepContext: String,
    ): ToolResult {
        val llmResult =
            llmGateway.callLlm(
                type = PromptTypeEnum.ANALYSIS_REASONING_TOOL,
                responseSchema = LlmResponseWrapper(),
                quick = context.quick,
                mappingValue =
                    mapOf(
                        "taskParams" to params,
                        "stepContext" to stepContext,
                    ),
            )

        val enhancedOutput =
            llmResult.result.response
                .trim()
                .ifEmpty { "Empty LLM response" }
        return ToolResult.success(
            "LLM",
            enhancedOutput,
            enhancedOutput,
        )
    }
}
