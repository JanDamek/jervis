package com.jervis.service.git

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.ClientMongoRepository
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
        val initialBranch = getBranchName(project)
        val sparseCheckoutPath =
            if (project.projectPath != null && project.overrides?.gitRemoteUrl == null) {
                project.projectPath
            } else {
                null
            }

        try {
            gitRemoteClient
                .clone(
                    repoUrl = repoUrl,
                    targetPath = gitDir,
                    branch = initialBranch,
                    envVars = envVars,
                    sparseCheckoutPath = sparseCheckoutPath,
                ).collectWithLogging(logger, project.name)
            logger.info { "Successfully cloned repository for project: ${project.name}" }
        } catch (e: Exception) {
            if (!isBranchNotFoundError(e.message)) {
                throw e
            }

            logger.warn { "Branch '$initialBranch' not found for ${project.name}. Attempting fallback discovery." }
            // Discover branches from remote and choose likely default
            val (defaultRemote, remoteBranches) = runLsRemote(repoUrl, envVars)
            val chosen = chooseLikelyDefaultBranch(defaultRemote, remoteBranches)
            logger.info { "Fallback selected branch '$chosen' for ${project.name}" }

            // Update client defaultBranch and persist
            updateClientDefaultBranch(project, chosen)

            // Clean any partial directory to allow re-clone
            safeDeleteDirectory(gitDir)
            gitDir.parent?.toFile()?.mkdirs()

            // Retry clone with chosen branch
            gitRemoteClient
                .clone(
                    repoUrl = repoUrl,
                    targetPath = gitDir,
                    branch = chosen,
                    envVars = envVars,
                    sparseCheckoutPath = sparseCheckoutPath,
                ).collectWithLogging(logger, project.name)
            logger.info { "Successfully cloned repository for project: ${project.name} on fallback branch '$chosen'" }
        }
    }

    private suspend fun pullRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitCredentialsManager.GitAuthContext?,
    ) {
        logger.info { "Pulling latest changes for project: ${project.name}" }

        val repoUrl = getRepositoryUrl(project) ?: throw IllegalStateException("No repository URL")
        val envVars = buildGitEnvironment(authContext)
        val initialBranch = getBranchName(project)

        try {
            gitRemoteClient
                .pull(
                    repoUrl = repoUrl,
                    workingDir = gitDir,
                    branch = initialBranch,
                    envVars = envVars,
                ).collectWithLogging(logger, project.name)
            logger.info { "Successfully pulled latest changes for project: ${project.name}" }
        } catch (e: Exception) {
            if (!isBranchNotFoundError(e.message)) {
                throw e
            }
            logger.warn { "Branch '$initialBranch' not found during pull for ${project.name}. Attempting fallback discovery." }
            val (defaultRemote, remoteBranches) = runLsRemote(repoUrl, envVars)
            val chosen = chooseLikelyDefaultBranch(defaultRemote, remoteBranches)
            logger.info { "Fallback selected branch '$chosen' for ${project.name}" }
            updateClientDefaultBranch(project, chosen)

            gitRemoteClient
                .pull(
                    repoUrl = repoUrl,
                    workingDir = gitDir,
                    branch = chosen,
                    envVars = envVars,
                ).collectWithLogging(logger, project.name)
            logger.info { "Successfully pulled latest changes for project: ${project.name} on fallback branch '$chosen'" }
        }
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

    // ===== Branch discovery + fallback helpers =====

    private fun isBranchNotFoundError(message: String?): Boolean {
        if (message == null) return false
        val m = message.lowercase()
        return m.contains("remote branch") && m.contains("not found") ||
            m.contains("pathspec") && m.contains("did not match") ||
            m.contains("invalid reference") ||
            m.contains("couldn't find remote ref") ||
            m.contains("could not find remote ref")
    }

    private fun runLsRemote(
        repoUrl: String,
        env: Map<String, String>,
    ): Pair<String?, List<String>> {
        val processBuilder = ProcessBuilder("git", "ls-remote", "--heads", "--symref", repoUrl)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().putAll(env)
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw IllegalStateException("git ls-remote failed ($exit): ${output.trim()}")
        }

        var defaultBranch: String? = null
        val branches = mutableListOf<String>()
        output.lineSequence().forEach { line ->
            if (line.startsWith("ref:") && line.contains("\tHEAD")) {
                defaultBranch = line.substringAfter("refs/heads/").substringBefore('\t')
            }
            if ("refs/heads/" in line && !line.startsWith("ref:")) {
                val name = line.substringAfter("refs/heads/").trim()
                branches.add(name.split("\t", " ").first())
            }
        }
        return defaultBranch to branches.distinct().sorted()
    }

    private fun chooseLikelyDefaultBranch(
        defaultRemote: String?,
        branches: List<String>,
    ): String {
        val normalized = branches.map { it.trim() }
        if (!defaultRemote.isNullOrBlank() && normalized.contains(defaultRemote)) return defaultRemote
        val candidates = listOf("main", "master", "trunk", "develop", "dev")
        val found = candidates.firstOrNull { c -> normalized.any { it.equals(c, ignoreCase = true) } }
        return found ?: normalized.firstOrNull() ?: "main"
    }

    private suspend fun updateClientDefaultBranch(
        project: ProjectDocument,
        newBranch: String,
    ) {
        val client = clientMongoRepository.findById(project.clientId)
        if (client != null && client.defaultBranch != newBranch) {
            val updated = client.copy(defaultBranch = newBranch)
            clientMongoRepository.save(updated)
            logger.info { "Updated client ${client.id} defaultBranch to '$newBranch'" }
        }
    }

    private fun safeDeleteDirectory(dir: Path) {
        try {
            val file = dir.toFile()
            if (!file.exists()) return
            file.walkBottomUp().forEach { it.delete() }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cleanup directory $dir before fallback clone" }
        }
    }

    /**
     * Get the current branch name for a repository path.
     * Returns client's defaultBranch as fallback if:
     * - Directory is not a Git repository (no .git directory)
     * - Git command fails
     * - Branch is in detached HEAD state
     */
    suspend fun getCurrentBranch(
        repositoryPath: Path,
        clientId: org.bson.types.ObjectId? = null,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                // Check if .git directory exists before attempting git command
                val gitDir = repositoryPath.resolve(".git")
                if (!gitDir.toFile().exists()) {
                    val fallback = getClientDefaultBranch(clientId)
                    logger.debug { "No .git directory at $repositoryPath, using fallback branch '$fallback'" }
                    return@withContext fallback
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
                        val fallback = getClientDefaultBranch(clientId)
                        logger.debug { "Repository at $repositoryPath is in detached HEAD state, using fallback '$fallback'" }
                        return@withContext fallback
                    }
                    logger.debug { "Retrieved Git branch for $repositoryPath: $branch" }
                    branch
                } else {
                    val fallback = getClientDefaultBranch(clientId)
                    logger.debug { "Git command failed for repository: $repositoryPath (exit code: $exitCode), using fallback '$fallback'" }
                    fallback
                }
            } catch (e: Exception) {
                val fallback = getClientDefaultBranch(clientId)
                logger.debug(e) { "Could not get Git branch for repository: $repositoryPath, using fallback '$fallback'" }
                fallback
            }
        }

    /**
     * Get client's default branch from database, or fallback to "master" if not found
     */
    private suspend fun getClientDefaultBranch(clientId: org.bson.types.ObjectId?): String {
        if (clientId == null) return "master"
        return try {
            clientMongoRepository.findById(clientId)?.defaultBranch ?: "master"
        } catch (e: Exception) {
            logger.debug(e) { "Failed to fetch client defaultBranch for $clientId, using 'master'" }
            "master"
        }
    }
}
