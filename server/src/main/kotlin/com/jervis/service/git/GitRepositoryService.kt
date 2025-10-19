package com.jervis.service.git

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

/**
 * Service for Git repository management operations.
 * Handles cloning, pulling, and credential configuration for Git repositories.
 */
@Service
class GitRepositoryService(
    private val directoryStructureService: DirectoryStructureService,
    private val credentialsManager: GitCredentialsManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Clone or update Git repository for the project.
     * TODO: Refactor to use Client mono-repo URL + project path instead of project-level URL
     */
    suspend fun cloneOrUpdateRepository(project: ProjectDocument): Result<Path> =
        withContext(Dispatchers.IO) {
            try {
                // TODO: Fetch ClientDocument and use client.monoRepoUrl + project.projectPath
                logger.warn { "Git operations temporarily disabled - needs refactoring to use client mono-repo" }
                val gitDir = directoryStructureService.projectGitDir(project)
                Result.success(gitDir)
            } catch (e: Exception) {
                logger.error(e) { "Failed to clone/update repository for project ${project.name}" }
                Result.failure(e)
            }
        }

    // TODO: Refactor these methods to use Client mono-repo configuration
    private suspend fun cloneRepository(
        project: ProjectDocument,
        gitDir: Path,
        repoUrl: String,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.warn { "Git clone temporarily disabled - needs refactoring" }
    }

    private suspend fun pullRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.warn { "Git pull temporarily disabled - needs refactoring" }
    }

    private fun executeGitCommand(
        command: List<String>,
        workingDir: Path?,
        envVars: Map<String, String>,
        operationName: String,
    ) {
        val processBuilder = ProcessBuilder(command)
        workingDir?.let { processBuilder.directory(it.toFile()) }

        processBuilder.environment().putAll(envVars)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Git $operationName failed with exit code $exitCode: $output")
        }

        logger.info { "Git $operationName completed successfully" }
    }

    /**
     * Validate that repository is accessible with current credentials.
     * TODO: Refactor to use Client mono-repo URL
     */
    suspend fun validateRepositoryAccess(project: ProjectDocument): Boolean =
        withContext(Dispatchers.IO) {
            logger.warn { "Git validation temporarily disabled - needs refactoring to use client mono-repo" }
            false
        }

    /**
     * Pull latest changes from remote repository.
     * TODO: Refactor to use Client mono-repo URL
     */
    suspend fun pullLatestChanges(project: ProjectDocument): Result<Unit> =
        withContext(Dispatchers.IO) {
            logger.warn { "Git pull temporarily disabled - needs refactoring to use client mono-repo" }
            Result.success(Unit)
        }
}
