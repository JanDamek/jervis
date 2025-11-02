package com.jervis.service.git

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Service for Git repository management operations.
 * Handles cloning, pulling, and credential configuration for Git repositories.
 *
 * Supports both:
 * - Standalone project-repositories-Client mono-repositories (shared across multiple projects)
 *
 * Parallel processing workflow:
 * - fetchMonoRepo() - Fast git fetch (only history, no files)
 * - cloneOrPullMonoRepo() - Full clone/pull with physical files
 * These can run in parallel: fetch → index metadata (parallel) → clone/pull → index code
 */
@Service
class GitRepositoryService(
    private val directoryStructureService: DirectoryStructureService,
    private val credentialsManager: GitCredentialsManager,
    private val clientMongoRepository: ClientMongoRepository,
    private val gitRemoteClient: GitRemoteClient,
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

    /**
     * Resolve Git repository URL for a project following precedence rules:
     * 1. Project Override (project.overrides.gitRemoteUrl) - highest priority
     * 2. Client Mono-Repo (client.monoRepoUrl) - shared repository for multiple projects
     * 3. None - no Git configuration available
     */
    private suspend fun getRepositoryUrl(project: ProjectDocument): String? {
        // Priority 1: Project-specific Git URL override
        project.overrides?.gitRemoteUrl?.let {
            logger.info { "Using project-specific Git URL for ${project.name}" }
            return it
        }

        // Priority 2: Client mono-repo URL
        val client = clientMongoRepository.findById(project.clientId)
        client?.monoRepoUrl?.let {
            logger.info { "Using client mono-repo URL for ${project.name}" }
            if (project.projectPath != null) {
                logger.info { "Project path within mono-repo: ${project.projectPath}" }
            }
            return it
        }

        // No Git configuration
        logger.debug { "No Git repository URL configured for project ${project.name}" }
        return null
    }

    private suspend fun cloneRepository(
        project: ProjectDocument,
        gitDir: Path,
        repoUrl: String,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.info { "Cloning repository from $repoUrl to $gitDir" }

        gitDir.parent?.toFile()?.mkdirs()

        val envVars = buildGitEnvironment(authContext)
        val branch = getBranchName(project)
        val sparseCheckoutPath =
            if (project.projectPath != null && project.overrides?.gitRemoteUrl == null) {
                project.projectPath
            } else {
                null
            }

        gitRemoteClient
            .clone(
                repoUrl = repoUrl,
                targetPath = gitDir,
                branch = branch,
                envVars = envVars,
                sparseCheckoutPath = sparseCheckoutPath,
            ).collectWithLogging(logger, project.name)

        logger.info { "Successfully cloned repository for project: ${project.name}" }
    }

    private suspend fun pullRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.info { "Pulling latest changes for project: ${project.name}" }

        val repoUrl = getRepositoryUrl(project) ?: throw IllegalStateException("No repository URL")
        val envVars = buildGitEnvironment(authContext)
        val branch = getBranchName(project)

        gitRemoteClient
            .pull(
                repoUrl = repoUrl,
                workingDir = gitDir,
                branch = branch,
                envVars = envVars,
            ).collectWithLogging(logger, project.name)

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

    // ========== Mono-Repo Methods ==========

    /**
     * Fast fetch for mono-repo (only history, no files).
     * Used for quick commit metadata retrieval before full clone.
     * Can run in parallel with indexing.
     */
    suspend fun fetchMonoRepo(
        client: ClientDocument,
        monoRepoConfig: MonoRepoConfig,
    ): Result<Path> =
        withContext(Dispatchers.IO) {
            try {
                val gitDir = directoryStructureService.clientMonoRepoGitDir(client.id, monoRepoConfig.id)
                val authContext = credentialsManager.prepareMonoRepoAuthentication(client, monoRepoConfig)

                if (gitDir.toFile().exists() && gitDir.resolve(".git").toFile().exists()) {
                    logger.info { "Mono-repo ${monoRepoConfig.name} exists, fetching latest commits..." }
                    fetchMonoRepoChanges(gitDir, monoRepoConfig.defaultBranch, authContext)
                } else {
                    logger.info { "Mono-repo ${monoRepoConfig.name} does not exist, initializing bare fetch..." }
                    gitDir.parent?.toFile()?.mkdirs()
                    cloneMonoRepoForFetch(
                        gitDir,
                        monoRepoConfig.repositoryUrl,
                        monoRepoConfig.defaultBranch,
                        authContext,
                    )
                }

                Result.success(gitDir)
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch mono-repo ${monoRepoConfig.name}" }
                Result.failure(e)
            }
        }

    private suspend fun fetchMonoRepoChanges(
        gitDir: Path,
        branch: String,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        val repoUrl = extractRepoUrlFromGitConfig(gitDir) ?: throw IllegalStateException("Cannot determine remote URL")
        val envVars = buildGitEnvironment(authContext)

        gitRemoteClient
            .fetch(
                repoUrl = repoUrl,
                workingDir = gitDir,
                branch = branch,
                envVars = envVars,
            ).collectWithLogging(logger, "mono-repo")

        logger.info { "Successfully fetched latest commits for mono-repo" }
    }

    private suspend fun cloneMonoRepoForFetch(
        gitDir: Path,
        repoUrl: String,
        branch: String,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        val envVars = buildGitEnvironment(authContext)

        gitRemoteClient
            .clone(
                repoUrl = repoUrl,
                targetPath = gitDir,
                branch = branch,
                envVars = envVars,
                sparseCheckoutPath = null,
            ).collectWithLogging(logger, "mono-repo")

        logger.info { "Successfully initialized mono-repo for fetch" }
    }

    // ========== Shared Methods ==========

    /**
     * Extract repository URL from .git/config.
     * Returns null if cannot be determined.
     */
    private fun extractRepoUrlFromGitConfig(gitDir: Path): String? =
        try {
            val processBuilder =
                ProcessBuilder("git", "config", "--get", "remote.origin.url")
                    .directory(gitDir.toFile())
                    .redirectErrorStream(true)

            val process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not extract repo URL from git config at $gitDir" }
            null
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
