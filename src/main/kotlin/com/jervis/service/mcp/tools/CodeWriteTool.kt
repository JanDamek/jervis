package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.ToolResponseBuilder
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Service
class CodeWriteTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.CODE_WRITE

    @Serializable
    data class CodeWriteParams(
        val targetPath: String = "",
        val patchType: String = "unified",
        val patch: String = "",
        val description: String = "",
        val createNewFile: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): CodeWriteParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CODE_WRITE,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = CodeWriteParams(),
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
        return try {
            logger.info("Executing CodeWrite tool with task: $taskDescription")

            val params = parseTaskDescription(taskDescription, context, stepContext)

            return performCodeWrite(params, context)
        } catch (e: Exception) {
            logger.error("CodeWrite tool execution failed: ${e.message}", e)
            ToolResult.error(
                output = "CodeWrite tool failed: ${e.message}",
                message = "Failed to execute code write operation: ${e.message}",
            )
        }
    }

    private suspend fun performCodeWrite(
        params: CodeWriteParams,
        context: TaskContext,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val projectPath = Path.of(context.projectDocument.path)
                val targetFile = projectPath.resolve(params.targetPath)

                when (params.patchType) {
                    "replacement" -> handleFileReplacement(targetFile, params)
                    "unified" -> handleUnifiedDiff(targetFile, params)
                    "inline" -> handleInlineModification(targetFile, params)
                    else ->
                        ToolResult.error(
                            output = "Unsupported patch type: ${params.patchType}",
                            message = "Patch type must be one of: unified, inline, replacement",
                        )
                }
            } catch (e: Exception) {
                logger.error("Code write operation failed: ${e.message}", e)
                ToolResult.error(
                    output = "Code write failed: ${e.message}",
                    message = e.message,
                )
            }
        }

    private fun handleFileReplacement(
        targetFile: Path,
        params: CodeWriteParams,
    ): ToolResult =
        try {
            // Create parent directories if they don't exist
            targetFile.parent?.let { Files.createDirectories(it) }

            targetFile.writeText(params.patch)

            val action = if (params.createNewFile) "Created" else "Updated"
            val details = buildString {
                appendLine("Operation: ${if (params.createNewFile) "Created new file" else "Replaced file content"}")
                if (params.description.isNotBlank()) {
                    appendLine("Description: ${params.description}")
                }
            }
            ToolResult.fileOperation(
                toolName = "CODE_WRITE",
                fileName = params.targetPath,
                action = action,
                details = details
            )
        } catch (e: Exception) {
            ToolResult.error(
                output = "Failed to write file: ${e.message}",
                message = "File write operation failed",
            )
        }

    private fun handleUnifiedDiff(
        targetFile: Path,
        params: CodeWriteParams,
    ): ToolResult {
        return try {
            if (!Files.exists(targetFile)) {
                return ToolResult.error(
                    output = "Target file does not exist: ${params.targetPath}",
                    message = "Cannot apply unified diff to non-existent file. Use replacement patch type for new files.",
                )
            }

            targetFile.readText()

            // For now, we'll treat the patch as the new content
            // In a full implementation, you would parse the unified diff and apply it
            targetFile.writeText(params.patch)

            val details = buildString {
                appendLine("Operation: Applied patch")
                if (params.description.isNotBlank()) {
                    appendLine("Description: ${params.description}")
                }
                appendLine()
                appendLine("Note: For production use, implement proper unified diff parsing and application.")
            }
            ToolResult.fileOperation(
                toolName = "CODE_WRITE",
                fileName = params.targetPath,
                action = "Patched",
                details = details
            )
        } catch (e: Exception) {
            ToolResult.error(
                output = "Failed to apply unified diff: ${e.message}",
                message = "Unified diff application failed",
            )
        }
    }

    private fun handleInlineModification(
        targetFile: Path,
        params: CodeWriteParams,
    ): ToolResult {
        return try {
            if (!Files.exists(targetFile)) {
                return ToolResult.error(
                    output = "Target file does not exist: ${params.targetPath}",
                    message = "Cannot apply inline modifications to non-existent file",
                )
            }

            // For now, we'll treat this as a simple replacement
            // In a full implementation, you would parse inline modification instructions
            targetFile.writeText(params.patch)

            val details = buildString {
                appendLine("Operation: Inline modification")
                if (params.description.isNotBlank()) {
                    appendLine("Description: ${params.description}")
                }
                appendLine()
                appendLine("Note: For production use, implement proper inline modification parsing.")
            }
            ToolResult.fileOperation(
                toolName = "CODE_WRITE",
                fileName = params.targetPath,
                action = "Modified",
                details = details
            )
        } catch (e: Exception) {
            ToolResult.error(
                output = "Failed to apply inline modification: ${e.message}",
                message = "Inline modification failed",
            )
        }
    }
}
