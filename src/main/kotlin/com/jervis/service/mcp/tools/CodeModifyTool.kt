package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@Service
class CodeModifyTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.CODE_MODIFY

    @Serializable
    data class CodeModifyParams(
        val targetPath: String = "",
        val patch: String = "",
        val createNewFile: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): CodeModifyParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CODE_MODIFY,
                responseSchema = CodeModifyParams(),
                quick = context.quick,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "userPrompt" to taskDescription,
                    ),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeCodeModifyOperation(parsed, context)
    }

    private suspend fun executeCodeModifyOperation(
        params: CodeModifyParams,
        context: TaskContext,
    ): ToolResult {
        val projectPath = Path.of(context.projectDocument.path)
        val targetFile = projectPath.resolve(params.targetPath)

        // Create parent directories if they don't exist
        targetFile.parent?.let { Files.createDirectories(it) }

        // Write the patch content to the file
        targetFile.writeText(params.patch)

        val action = if (params.createNewFile) "Created" else "Modified"
        val details =
            buildString {
                appendLine("Operation: ${if (params.createNewFile) "Created new file" else "Modified existing file"}")
                appendLine("File: ${params.targetPath}")
                appendLine("Content length: ${params.patch.length} characters")
            }

        return ToolResult.success("CODE_MODIFY", action, details)
    }
}
