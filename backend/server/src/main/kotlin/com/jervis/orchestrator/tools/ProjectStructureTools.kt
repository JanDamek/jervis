package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.project.ProjectService
import com.jervis.service.storage.DirectoryStructureService
import mu.KotlinLogging
import java.nio.file.Files

/**
 * Tools for accessing project directory structure.
 *
 * CRITICAL: This is the ONLY way to get project paths.
 * Never invent or construct paths manually - always use these tools.
 */
class ProjectStructureTools(
    private val task: TaskDocument,
    private val directoryStructureService: DirectoryStructureService,
    private val projectService: ProjectService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Get the absolute path to the project's Git repository directory.
        This is where the source code is located.
        Use this tool BEFORE any file operations or code analysis.

        Returns: Absolute path string like '/workspace/clients/{clientId}/projects/{projectId}/git'
        """,
    )
    fun getProjectGitPath(): String {
        val clientId = task.clientId
        val projectId =
            task.projectId
                ?: throw IllegalStateException("No project ID in task - cannot determine project path")

        val gitPath = directoryStructureService.projectGitDir(clientId, projectId)

        logger.info {
            "📁 PROJECT_GIT_PATH | path=$gitPath | " +
                "clientId=$clientId | projectId=$projectId | correlationId=${task.correlationId}"
        }

        return gitPath.toString()
    }

    @Tool
    @LLMDescription(
        """Get the absolute path to the project's root directory.
        This contains git/, documents/, uploads/, audio/, meetings/ subdirectories.

        Returns: Absolute path string like '/workspace/clients/{clientId}/projects/{projectId}'
        """,
    )
    fun getProjectRootPath(): String {
        val clientId = task.clientId
        val projectId =
            task.projectId
                ?: throw IllegalStateException("No project ID in task - cannot determine project path")

        val rootPath = directoryStructureService.projectDir(clientId, projectId)

        logger.info {
            "📁 PROJECT_ROOT_PATH | path=$rootPath | " +
                "clientId=$clientId | projectId=$projectId | correlationId=${task.correlationId}"
        }

        return rootPath.toString()
    }

    @Tool
    @LLMDescription(
        """Get the absolute path to the project's documents directory.
        This is where documentation files, PDFs, and other documents are stored.

        Returns: Absolute path string like '/workspace/clients/{clientId}/projects/{projectId}/documents'
        """,
    )
    fun getProjectDocumentsPath(): String {
        val clientId = task.clientId
        val projectId =
            task.projectId
                ?: throw IllegalStateException("No project ID in task - cannot determine project path")

        val docsPath = directoryStructureService.projectDocumentsDir(clientId, projectId)

        logger.info {
            "📁 PROJECT_DOCUMENTS_PATH | path=$docsPath | " +
                "clientId=$clientId | projectId=$projectId | correlationId=${task.correlationId}"
        }

        return docsPath.toString()
    }

    @Tool
    @LLMDescription(
        """Get information about the project including name, description, and whether it has Git repository.
        Use this to understand what project you're working with.

        Returns: JSON with projectName, projectDescription, hasGitRepo, gitRemoteUrl
        """,
    )
    suspend fun getProjectInfo(): String {
        val projectId =
            task.projectId
                ?: return """{"error": "No project context - this is a general chat without specific project"}"""

        val project =
            projectService.getProjectById(projectId)

        val gitPath = directoryStructureService.projectGitDir(task.clientId, projectId)
        val hasGitRepo = Files.exists(gitPath) && Files.isDirectory(gitPath)

        logger.info {
            "ℹ️ PROJECT_INFO | name=${project.name} | hasGit=$hasGitRepo | " +
                "projectId=$projectId | correlationId=${task.correlationId}"
        }

        return """
            { 
                "projectName": "${project.name}",
                "projectDescription": "${project.description ?: "No description"}",
                "hasGitRepo": $hasGitRepo,
                "projectId": "$projectId"
            }
            """.trimIndent()
    }

    @Tool
    @LLMDescription(
        """List subdirectories available in the project.
        Shows which directories exist: git, documents, uploads, audio, meetings.

        Returns: JSON array of existing subdirectory names
        """,
    )
    fun listProjectSubdirectories(): String {
        val projectId =
            task.projectId
                ?: return """{"error": "No project context"}"""

        val projectRoot = directoryStructureService.projectDir(task.clientId, projectId)
        val subdirs =
            listOf("git", "documents", "uploads", "audio", "meetings")
                .filter { Files.exists(projectRoot.resolve(it)) && Files.isDirectory(projectRoot.resolve(it)) }

        logger.info {
            "📂 PROJECT_SUBDIRS | subdirs=$subdirs | " +
                "projectId=$projectId | correlationId=${task.correlationId}"
        }

        return """{"subdirectories": ${subdirs.joinToString(",") { "\"$it\"" }}}"""
    }
}
