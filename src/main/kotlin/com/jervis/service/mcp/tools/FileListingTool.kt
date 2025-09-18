package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * Simple file listing tool that provides a clean tree structure of project files.
 * Shows all files with full paths in an organized tree format for easy navigation.
 */
@Service
class FileListingTool(
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.FILE_LISTING

    @Serializable
    data class FileListingParams(
        val maxDepth: Int = 10,
        val includeHidden: Boolean = false,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.debug { "FILE_LISTING_START: Executing simple file listing for task='$taskDescription'" }

            val params = parseTaskDescription(taskDescription)
            val projectPath = determineProjectPath(context)

            logger.debug { "FILE_LISTING_PROJECT_PATH: Using project path: $projectPath" }

            try {
                val fileTree = buildFileTree(projectPath, params)

                logger.debug { "FILE_LISTING_SUCCESS: Generated file tree for project" }

                ToolResult.ok(fileTree)
            } catch (e: Exception) {
                logger.error(e) { "FILE_LISTING_ERROR: Failed to generate file tree" }
                ToolResult.error("Failed to generate file tree: ${e.message}")
            }
        }

    private fun parseTaskDescription(taskDescription: String): FileListingParams {
        val maxDepth = extractMaxDepth(taskDescription)
        val includeHidden = taskDescription.contains("include hidden", ignoreCase = true)

        return FileListingParams(
            maxDepth = maxDepth,
            includeHidden = includeHidden,
        )
    }

    private fun extractMaxDepth(taskDescription: String): Int {
        val depthRegex = """depth\s*[=:]\s*(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        return depthRegex
            .find(taskDescription)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 10
    }

    private fun determineProjectPath(context: TaskContext): Path {
        val projectPath = context.projectDocument.path

        return when {
            projectPath.isBlank() -> {
                logger.warn { "FILE_LISTING_EMPTY_PATH: Project path is empty, using current directory" }
                Path.of(System.getProperty("user.dir"))
            }

            else -> {
                val path = Path.of(projectPath)
                if (Files.exists(path) && Files.isDirectory(path)) {
                    path
                } else {
                    logger.warn { "FILE_LISTING_INVALID_PATH: Invalid path '$projectPath', using current directory" }
                    Path.of(System.getProperty("user.dir"))
                }
            }
        }
    }

    private suspend fun buildFileTree(
        projectPath: Path,
        params: FileListingParams,
    ): String {
        val output = StringBuilder()

        output.appendLine("PROJECT FILE TREE")
        output.appendLine("Root: $projectPath")
        output.appendLine()

        buildTreeRecursive(projectPath, projectPath, "", params, 0, output)

        output.appendLine()
        output.appendLine("File listing complete. Use full paths for navigation and reference.")

        return output.toString()
    }

    private fun buildTreeRecursive(
        currentPath: Path,
        rootPath: Path,
        prefix: String,
        params: FileListingParams,
        currentDepth: Int,
        output: StringBuilder,
    ) {
        if (currentDepth > params.maxDepth) return

        try {
            val entries =
                Files.list(currentPath).use { stream ->
                    stream
                        .filter { path ->
                            when {
                                !params.includeHidden && path.isHidden() -> false
                                path.name.startsWith(".git") -> false // Always exclude .git
                                path.name == "target" && path.isDirectory() -> false // Exclude build artifacts
                                path.name == "node_modules" && path.isDirectory() -> false // Exclude node_modules
                                path.name == ".idea" && path.isDirectory() -> false // Exclude IDE files
                                else -> true
                            }
                        }.sorted { path1, path2 ->
                            // Directories first, then files, both alphabetically
                            when {
                                path1.isDirectory() && !path2.isDirectory() -> -1
                                !path1.isDirectory() && path2.isDirectory() -> 1
                                else -> path1.name.compareTo(path2.name, ignoreCase = true)
                            }
                        }.toList()
                }

            entries.forEachIndexed { index, path ->
                val isLast = index == entries.size - 1
                val connector = if (isLast) "└── " else "├── "
                val nextPrefix = if (isLast) "$prefix    " else "$prefix│   "

                rootPath.relativeTo(path).toString()
                path.toString()

                if (path.isDirectory()) {
                    output.appendLine("$prefix$connector${path.name}/")
                    buildTreeRecursive(path, rootPath, nextPrefix, params, currentDepth + 1, output)
                } else {
                    output.appendLine("$prefix$connector${path.name}")
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "FILE_LISTING_DIR_ERROR: Error reading directory $currentPath" }
            output.appendLine("$prefix└── [Error reading directory: ${e.message}]")
        }
    }
}
