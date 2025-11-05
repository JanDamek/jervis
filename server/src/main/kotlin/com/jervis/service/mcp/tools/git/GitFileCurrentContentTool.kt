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
    private val gitRepositoryService: com.jervis.service.git.GitRepositoryService,
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

            // Optional branch
            val branch = extractBranch(taskDescription)
            // Read file content
            val fileContent = readFileContent(projectId, filePath, maxLines, branch)

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
        // Allow optional branch parameter in description: branch: <name>
        return taskDescription.let { extractPathFromDescription(it) }
    }

    private fun extractPathFromDescription(text: String): String {
        // Try JSON-like key first
        Regex("""file(Path)?\s*[:=]\s*"([^"]+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[2]
        }
        // Try unquoted key=value or key: value
        Regex("""file(Path)?\s*[:=]\s*([^\n,]+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            val candidate = it.groupValues[2].trim()
            if (looksLikePath(candidate)) return candidate
        }
        // Fallback: scan tokens and pick the first token that looks like a path with a separator
        text
            .split("\n", " ", "\t", ",")
            .map { it.trim() }
            .firstOrNull { looksLikePath(it) }
            ?.let { return it }
        throw IllegalArgumentException("No file path found in: $text")
    }

    private fun looksLikePath(token: String): Boolean {
        if (token.isBlank()) return false
        val t = token.trim().trim('"', '\'', '`')
        if (t.equals("Get", ignoreCase = true) || t.equals("File", ignoreCase = true)) return false
        // Must contain a path separator and a dot extension somewhere
        val hasSep = t.contains("/") || t.contains("\\")
        val hasExt = t.contains('.')
        return hasSep && hasExt && !t.contains("://")
    }

    private fun extractBranch(taskDescription: String): String? {
        val pattern = Regex("""branch:\s*([^\s,]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(taskDescription)?.groupValues?.get(1)
    }

    private fun ensureBranchCheckedOut(
        gitDir: java.nio.file.Path,
        branch: String,
    ) {
        val fetch =
            ProcessBuilder("git", "fetch", "origin", branch)
                .directory(gitDir.toFile())
                .redirectErrorStream(true)
                .start()
        val fetchOut = fetch.inputStream.bufferedReader().use { it.readText() }
        val fetchExit = fetch.waitFor()
        if (fetchExit != 0) {
            throw IllegalStateException("git fetch failed for branch '$branch' in $gitDir: $fetchOut")
        }
        val checkout =
            ProcessBuilder("git", "checkout", branch).directory(gitDir.toFile()).redirectErrorStream(true).start()
        val checkoutOut = checkout.inputStream.bufferedReader().use { it.readText() }
        val checkoutExit = checkout.waitFor()
        if (checkoutExit != 0) {
            throw IllegalStateException("git checkout failed for branch '$branch' in $gitDir: $checkoutOut")
        }
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
        branch: String?,
    ): FileContent =
        withContext(Dispatchers.IO) {
            val project =
                projectMongoRepository.findById(projectId)
                    ?: throw IllegalStateException("Project not found: $projectId")

            var gitDir = directoryStructureService.projectGitDir(project)
            // Ensure repository exists and is a valid Git repo
            if (!gitDir.toFile().exists() || !gitDir.resolve(".git").toFile().exists()) {
                val cloneResult = gitRepositoryService.cloneOrUpdateRepository(project)
                gitDir =
                    cloneResult.getOrElse {
                        throw IllegalStateException(
                            "Git repository not available for project: ${project.name}: ${it.message}",
                        )
                    }
            }

            // Optional branch: checkout if provided
            if (!branch.isNullOrBlank()) {
                ensureBranchCheckedOut(gitDir, branch)
            }

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
