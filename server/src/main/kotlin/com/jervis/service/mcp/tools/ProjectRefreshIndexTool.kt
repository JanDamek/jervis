package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.IndexingService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * MCP Tool for reindexing projects
 * Provides functionality to reindex the current project using comprehensive indexing
 */
@Service
class ProjectRefreshIndexTool(
    private val llmGateway: LlmGateway,
    private val indexingService: IndexingService,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.PROJECT_REFRESH_INDEX_TOOL

    @Serializable
    data class ProjectRefreshIndexParams(
        val action: String = "reindex",
        val projectId: String? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): ProjectRefreshIndexParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PROJECT_REFRESH_INDEX_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = context.quick,
                responseSchema = ProjectRefreshIndexParams(),
            )
        return llmResponse.result
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val project = context.projectDocument
                logger.info { "Starting reindex operation for project: ${project.name}" }

                if (project.projectPath.isEmpty()) {
                    return@withContext ToolResult.error("Project has no path configured: ${project.name}")
                }

                // Perform comprehensive reindexing using all available indexing services
                val result = indexingService.indexProject(project)

                logger.info { "Successfully completed comprehensive reindex operation for project: ${project.name}" }

                ToolResult.success(
                    toolName = "REINDEX",
                    summary = "Project reindexing completed: ${result.processedFiles} files processed, ${result.errorFiles} errors",
                    content =
                        buildString {
                            appendLine("✅ Comprehensive Project Reindexing Completed")
                            appendLine()
                            appendLine("Project: ${project.name}")
                            appendLine("Path: ${project.projectPath}")
                            appendLine()
                            appendLine("Statistics:")
                            appendLine("• Processed files: ${result.processedFiles}")
                            appendLine("• Skipped files: ${result.skippedFiles}")
                            appendLine("• Error files: ${result.errorFiles}")
                            appendLine()
                            appendLine("The following operations were performed in parallel:")
                            appendLine("• Code files indexing - Extracted and embedded source code")
                            appendLine("• Text content indexing - Processed documentation and text files")
                            appendLine("• Comprehensive file descriptions - Generated detailed file summaries")
                            appendLine("• Git history indexing - Processed commit history and changes")
                            appendLine("• Documentation indexing - Processed project documentation and URLs")
                            appendLine("• Meeting transcripts indexing - Processed meeting recordings and transcripts")
                            appendLine("• Meeting audio indexing - Processed audio files from meetings")
                            appendLine("• Joern static analysis - Performed comprehensive code analysis")
                            appendLine("• Dependencies analysis - Analyzed project dependencies")
                            appendLine("• Class summaries generation - Created detailed class and method summaries")
                            appendLine("• Project descriptions update - Generated comprehensive project descriptions")
                            appendLine()
                            appendLine("The RAG system has been comprehensively updated with all project information.")
                            appendLine()
                            appendLine("Task description processed: $taskDescription")
                        },
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to reindex project: ${context.projectDocument.name}" }
                ToolResult.error("Failed to reindex project: ${e.message}")
            }
        }
}
