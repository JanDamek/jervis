package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
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

/**
 * Project Explore Structure tool that provides a clean tree structure of project files.
 * Shows all files with full paths in an organized tree format for easy navigation.
 */
@Service
class ProjectExploreStructureTool(
    override val promptRepository: PromptRepository,
    private val directoryStructureService: DirectoryStructureService,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.PROJECT_EXPLORE_STRUCTURE_TOOL

    @Serializable
    data class ProjectExploreStructureParams(
        val maxDepth: Int = 10,
        val includeHidden: Boolean = false,
        val path: String? = null,
    )

    private data class FileTreeResult(
        val content: String,
        val itemCount: Int,
    )

    private suspend fun parseTaskDescription(taskDescription: String): ProjectExploreStructureParams {
        val text = taskDescription.trim()

        val pathRegexes =
            listOf(
                Regex("""(?i)\b(path|dir(ectory)?|folder)\s*[:=]\s*["']?([^"'\n\r]+)["']?"""),
                Regex("""(?i)\b(vypi[sš]|list|show)\b[^.\n\r]*\s+["']?([./\\][^"'\n\r]+|/[^\s"']+|[A-Za-z]:(\\|/)[^"'\n\r]+)["']?"""),
            )
        val pathFromKeyword =
            pathRegexes
                .asSequence()
                .mapNotNull { re ->
                    re.find(text)?.groups?.let { g ->
                        // Skupina s cestou je poslední zachycená skupina v obou regexech
                        (g[g.size - 1]?.value)?.trim()
                    }
                }.firstOrNull()

        // 2) Hloubka
        val depthRegex = Regex("""(?i)\b(max?depth|depth|hloubka|urove[nň]|úroveň)\s*[:=]\s*(\d{1,3})\b""")
        val depth =
            depthRegex
                .find(text)
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
                ?.coerceIn(1, 50) ?: 10

        // 3) Skryté soubory
        val includeHidden =
            when {
                Regex("(?i)(includeHidden|hidden\\s*[:=]\\s*true|včetně\\s+skrytých|vcetne\\s+skrytych|show\\s+hidden)").containsMatchIn(
                    text,
                ) -> true

                Regex("(?i)(hidden\\s*[:=]\\s*false|bez\\s+skrytých|bez\\s+skrytych|hide\\s+hidden)").containsMatchIn(
                    text,
                ) -> false

                else -> false
            }

        return ProjectExploreStructureParams(
            maxDepth = depth,
            includeHidden = includeHidden,
            path = pathFromKeyword,
        )
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription)

        return executeProjectExploreStructureOperation(parsed, context)
    }

    private suspend fun executeProjectExploreStructureOperation(
        params: ProjectExploreStructureParams,
        context: TaskContext,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.debug {
                "PROJECT_EXPLORE_STRUCTURE_START: Executing file listing for maxDepth=${params.maxDepth}, includeHidden=${params.includeHidden}, pathOverride=${params.path ?: "<none>"}"
            }

            val projectPath = determineProjectPath(context, params.path)

            logger.debug { "PROJECT_EXPLORE_STRUCTURE_PROJECT_PATH: Using project path: $projectPath" }

            val fileTreeResult = buildFileTree(projectPath, params)

            logger.debug { "PROJECT_EXPLORE_STRUCTURE_SUCCESS: Generated file tree for project" }

            ToolResult.listingResult(
                toolName = name.name,
                itemType = "files and directories",
                rootInfo = "Root: $projectPath",
                listing = fileTreeResult.content,
            )
        }

    private fun determineProjectPath(
        context: TaskContext,
        overridePath: String?,
    ): Path {
        val projectGitPath =
            directoryStructureService.projectGitDir(
                context.clientDocument.id,
                context.projectDocument.id,
            )

        val candidates =
            buildList {
                if (!overridePath.isNullOrBlank()) add(overridePath)
                add(projectGitPath.toString())
                add(System.getProperty("user.dir"))
            }

        for (candidate in candidates) {
            kotlin.runCatching { Path.of(candidate) }.getOrNull()?.let { p ->
                if (Files.exists(p) && Files.isDirectory(p)) return p
            }
        }

        return projectGitPath
    }

    private suspend fun buildFileTree(
        projectPath: Path,
        params: ProjectExploreStructureParams,
    ): FileTreeResult {
        val treeContent = StringBuilder()
        var itemCount: Int

        fun buildTreeRecursiveWithCount(
            currentPath: Path,
            rootPath: Path,
            prefix: String,
            params: ProjectExploreStructureParams,
            currentDepth: Int,
            output: StringBuilder,
        ): Int {
            if (currentDepth > params.maxDepth) return 0
            var count = 0

            val entries =
                Files
                    .list(currentPath)
                    .use { stream ->
                        stream
                            .filter { path ->
                                when {
                                    !params.includeHidden && path.isHidden() -> false
                                    path.name.startsWith(".git") -> false
                                    path.name == "target" && path.isDirectory() -> false
                                    path.name == "node_modules" && path.isDirectory() -> false
                                    path.name == ".idea" && path.isDirectory() -> false
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
                    }.toList()

            entries.forEachIndexed { index, path ->
                val isLast = index == entries.size - 1
                val connector = if (isLast) "└── " else "├── "
                val nextPrefix = if (isLast) "$prefix    " else "$prefix│   "
                count++

                if (path.isDirectory()) {
                    output.appendLine("$prefix$connector${path.name}/")
                    count += buildTreeRecursiveWithCount(path, rootPath, nextPrefix, params, currentDepth + 1, output)
                } else {
                    output.appendLine("$prefix$connector${path.name}")
                }
            }
            return count
        }

        itemCount = buildTreeRecursiveWithCount(projectPath, projectPath, "", params, 0, treeContent)

        return FileTreeResult(
            content = treeContent.toString().trimEnd(),
            itemCount = itemCount,
        )
    }
}
