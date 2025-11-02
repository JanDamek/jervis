package com.jervis.service.mcp.tools.git

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * MCP Tool: git_file_current_content
 *
 * Get current content of a file from Git repository.
 * Use this when you need to read actual source code.
 * WARNING: Large files may be truncated.
 *
 * Usage:
 * - git_file_current_content(filePath: "server/src/.../EmailService.kt")
 * - git_file_current_content(filePath: "...", maxLines: 500)
 *
 * Returns:
 * - content: File content
 * - lineCount: Total lines
 * - truncated: Boolean if content was truncated
 */
@Service
class GitFileCurrentContentTool(
    private val projectMongoRepository: ProjectMongoRepository,
    private val directoryStructureService: DirectoryStructureService,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.GIT_FILE_CURRENT_CONTENT_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "GIT_FILE_CURRENT_CONTENT: Reading file content" }

        return try {
            val projectId =
                plan.projectDocument?.id
                    ?: return ToolResult.error(
                        "No project context available",
                        "Project required for git_file_current_content",
                    )

            // Extract parameters
            val filePath = extractFilePath(taskDescription)
            val maxLines = extractMaxLinesOptional(taskDescription) ?: 1000

            // Read file content
            val fileContent = readFileContent(projectId, filePath, maxLines)

            ToolResult.success(
                toolName = name.name,
                summary =
                    "File content: $filePath (${fileContent.lineCount} lines${if (fileContent.truncated) ", truncated" else ""})",
                content =
                    buildString {
                        appendLine("File: $filePath")
                        appendLine("Total lines: ${fileContent.lineCount}")
                        if (fileContent.truncated) {
                            appendLine("Truncated: Yes (showing first $maxLines lines)")
                        }
                        appendLine()
                        appendLine("Content:")
                        appendLine("```")
                        appendLine(fileContent.content)
                        appendLine("```")
                    },
            )
        } catch (e: Exception) {
            logger.error(e) { "GIT_FILE_CURRENT_CONTENT: Failed to read file" }
            ToolResult.error(
                output = "Failed to read file content: ${e.message}",
                message = e.message,
            )
        }
    }

    private fun extractFilePath(taskDescription: String): String {
        val pathPattern = Regex("""(?:file(?:Path)?:\s*)?([^\s,]+)""", RegexOption.IGNORE_CASE)
        return pathPattern.find(taskDescription)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No file path found in: $taskDescription")
    }

    private fun extractMaxLinesOptional(taskDescription: String): Int? {
        val maxLinesPattern = Regex("""maxLines:\s*(\d+)""", RegexOption.IGNORE_CASE)
        return maxLinesPattern
            .find(taskDescription)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private suspend fun readFileContent(
        projectId: ObjectId,
        filePath: String,
        maxLines: Int,
    ): FileContent =
        withContext(Dispatchers.IO) {
            val project =
                projectMongoRepository.findById(projectId)
                    ?: throw IllegalStateException("Project not found: $projectId")

            val gitDir = directoryStructureService.projectGitDir(project)
            val fullPath = gitDir.resolve(filePath)

            if (!Files.exists(fullPath)) {
                throw IllegalStateException("File not found: $filePath")
            }

            val allLines = fullPath.readText().lines()
            val lineCount = allLines.size

            val content =
                if (lineCount <= maxLines) {
                    allLines.joinToString("\n")
                } else {
                    allLines.take(maxLines).joinToString("\n") + "\n\n... (${lineCount - maxLines} more lines omitted)"
                }

            FileContent(
                content = content,
                lineCount = lineCount,
                truncated = lineCount > maxLines,
            )
        }

    private data class FileContent(
        val content: String,
        val lineCount: Int,
        val truncated: Boolean,
    )
}
