package com.jervis.service.git

import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
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
    private val clientMongoRepository: ClientMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Clone or update Git repository for the project.
     * Uses client mono-repo URL + project path if available.
     */
    suspend fun cloneOrUpdateRepository(project: ProjectDocument): Result<Path> =
        withContext(Dispatchers.IO) {
            try {
                val gitDir = directoryStructureService.projectGitDir(project)
                val authContext = credentialsManager.prepareGitAuthentication(project)

                // Check if repository already exists
                if (gitDir.toFile().exists() && gitDir.resolve(".git").toFile().exists()) {
                    logger.info { "Repository exists, pulling latest changes for project: ${project.name}" }
                    pullRepository(project, gitDir, authContext)
                } else {
                    logger.info { "Repository does not exist, cloning for project: ${project.name}" }
                    val repoUrl = getRepositoryUrl(project)
                    if (repoUrl == null) {
                        logger.warn { "No Git repository URL configured for project: ${project.name}" }
                        return@withContext Result.failure(IllegalStateException("No Git repository URL configured"))
                    }
                    cloneRepository(project, gitDir, repoUrl, authContext)
                }

                // Configure Git repository after clone/pull
                credentialsManager.configureGitRepository(project, gitDir, authContext)

                Result.success(gitDir)
            } catch (e: Exception) {
                logger.error(e) { "Failed to clone/update repository for project ${project.name}" }
                Result.failure(e)
            }
        }

    private suspend fun getRepositoryUrl(project: ProjectDocument): String? {
        // Priority 1: Project-specific Git URL override
        project.overrides?.gitRemoteUrl?.let { return it }

        // Priority 2: Client mono-repo URL (legacy fallback)
        val client = clientMongoRepository.findById(project.clientId)
        return client?.monoRepoUrl
    }

    private suspend fun cloneRepository(
        project: ProjectDocument,
        gitDir: Path,
        repoUrl: String,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.info { "Cloning repository from $repoUrl to $gitDir" }

        // Create parent directory
        gitDir.parent?.toFile()?.mkdirs()

        val envVars = buildGitEnvironment(authContext)
        val branch = getBranchName(project)

        // TODO: projectPath removed - mono-repo subpath support needs redesign
        // For now, clone full repository
        executeGitCommand(
            listOf("git", "clone", "--branch", branch, repoUrl, gitDir.toString()),
            null,
            envVars,
            "clone",
        )

        /* DISABLED - sparse checkout for mono-repo subpath
        if (project.projectPath != null) {
            executeGitCommand(
                listOf("git", "clone", "--no-checkout", repoUrl, gitDir.toString()),
                null,
                envVars,
                "clone",
            )
            executeGitCommand(
                listOf("git", "sparse-checkout", "init", "--cone"),
                gitDir,
                envVars,
                "sparse-checkout-init",
            )
            executeGitCommand(
                listOf("git", "sparse-checkout", "set", project.projectPath),
                gitDir,
                envVars,
                "sparse-checkout-set",
            )

            executeGitCommand(
                listOf("git", "checkout", branch),
                gitDir,
                envVars,
                "checkout",
            )
        }
         */

        logger.info { "Successfully cloned repository for project: ${project.name}" }
    }

    private suspend fun pullRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.info { "Pulling latest changes for project: ${project.name}" }

        val envVars = buildGitEnvironment(authContext)
        val branch = getBranchName(project)

        // Fetch and pull
        executeGitCommand(
            listOf("git", "fetch", "origin"),
            gitDir,
            envVars,
            "fetch",
        )

        executeGitCommand(
            listOf("git", "pull", "origin", branch),
            gitDir,
            envVars,
            "pull",
        )

        logger.info { "Successfully pulled latest changes for project: ${project.name}" }
    }

    private suspend fun getBranchName(project: ProjectDocument): String {
        val client = clientMongoRepository.findById(project.clientId)
        return client?.defaultBranch ?: "main"
    }

    private fun buildGitEnvironment(authContext: GitCredentialsManager.GitAuthContext?): Map<String, String> {
        val envVars = mutableMapOf<String, String>()

        if (authContext != null) {
            // SSH authentication
            if (authContext.sshWrapperPath != null) {
                envVars["GIT_SSH"] = authContext.sshWrapperPath.toString()
            }

            // HTTPS authentication
            if (authContext.httpsUsername != null && authContext.httpsPassword != null) {
                envVars["GIT_ASKPASS"] = "echo"
                envVars["GIT_USERNAME"] = authContext.httpsUsername
                envVars["GIT_PASSWORD"] = authContext.httpsPassword
            } else if (authContext.decryptedHttpsToken != null) {
                envVars["GIT_ASKPASS"] = "echo"
                envVars["GIT_PASSWORD"] = authContext.decryptedHttpsToken
            }

            // GPG signing
            if (authContext.gpgKeyId != null) {
                envVars["GPG_KEY_ID"] = authContext.gpgKeyId
            }
        }

        return envVars
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

    /**
     * Get the current Git commit hash for a repository path.
     * Moved from HistoricalVersioningService - this is the right place for Git operations.
     *
     * Returns null if:
     * - Directory is not a Git repository (no .git directory)
     * - Git command fails
     * - Any error occurs
     */
    suspend fun getCurrentCommitHash(repositoryPath: Path): String? =
        withContext(Dispatchers.IO) {
            try {
                // Check if .git directory exists before attempting git command
                val gitDir = repositoryPath.resolve(".git")
                if (!gitDir.toFile().exists()) {
                    logger.debug { "No .git directory at $repositoryPath, skipping git commit hash retrieval" }
                    return@withContext null
                }

                val processBuilder =
                    ProcessBuilder("git", "rev-parse", "HEAD")
                        .directory(repositoryPath.toFile())
                        .redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    val hash = process.inputStream.bufferedReader().use { it.readText().trim() }
                    logger.debug { "Retrieved Git commit hash for $repositoryPath: $hash" }
                    hash
                } else {
                    logger.debug { "Git command failed for repository: $repositoryPath (exit code: $exitCode)" }
                    null
                }
            } catch (e: Exception) {
                logger.debug(e) { "Could not get Git commit hash for repository: $repositoryPath" }
                null
            }
        }

    /**
     * Get the current branch name for a repository path.
     * Returns "main" as default if:
     * - Directory is not a Git repository (no .git directory)
     * - Git command fails
     * - Branch is in detached HEAD state
     */
    suspend fun getCurrentBranch(repositoryPath: Path): String =
        withContext(Dispatchers.IO) {
            try {
                // Check if .git directory exists before attempting git command
                val gitDir = repositoryPath.resolve(".git")
                if (!gitDir.toFile().exists()) {
                    logger.debug { "No .git directory at $repositoryPath, using default branch 'main'" }
                    return@withContext "main"
                }

                val processBuilder =
                    ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        .directory(repositoryPath.toFile())
                        .redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    val branch = process.inputStream.bufferedReader().use { it.readText().trim() }
                    // Handle detached HEAD state
                    if (branch == "HEAD") {
                        logger.debug { "Repository at $repositoryPath is in detached HEAD state, using default 'main'" }
                        return@withContext "main"
                    }
                    logger.debug { "Retrieved Git branch for $repositoryPath: $branch" }
                    branch
                } else {
                    logger.debug { "Git command failed for repository: $repositoryPath (exit code: $exitCode), using default 'main'" }
                    "main"
                }
            } catch (e: Exception) {
                logger.debug(e) { "Could not get Git branch for repository: $repositoryPath, using default 'main'" }
                "main"
            }
        }
}
