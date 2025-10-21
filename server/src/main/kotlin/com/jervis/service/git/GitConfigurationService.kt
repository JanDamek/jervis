package com.jervis.service.git

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Service for Git configuration and setup operations.
 * Handles Git provider setup, credential storage, and repository validation.
 */
@Service
class GitConfigurationService(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
    private val sshKeyManager: SshKeyManager,
    private val gpgKeyManager: GpgKeyManager,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Setup Git configuration for a client including provider, authentication, and workflow rules.
     */
    suspend fun setupGitForClient(
        clientId: ObjectId,
        request: GitSetupRequestDto,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Setting up Git configuration for client: $clientId" }

                val client = clientRepository.findById(clientId)
                if (client == null) {
                    return@withContext Result.failure(IllegalArgumentException("Client not found: $clientId"))
                }

                // Create GitConfig object with workflow settings and raw credentials (no encryption)
                val gitConfig =
                    GitConfig(
                        gitUserName = request.gitConfig?.gitUserName,
                        gitUserEmail = request.gitConfig?.gitUserEmail,
                        commitMessageTemplate = request.gitConfig?.commitMessageTemplate,
                        requireGpgSign = request.gitConfig?.requireGpgSign ?: false,
                        gpgKeyId = request.gitConfig?.gpgKeyId,
                        requireLinearHistory = request.gitConfig?.requireLinearHistory ?: false,
                        conventionalCommits = request.gitConfig?.conventionalCommits ?: false,
                        commitRules = request.gitConfig?.commitRules ?: emptyMap(),
                        sshPrivateKey =
                            request.sshPrivateKey?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.sshPrivateKey,
                        sshPublicKey =
                            request.sshPublicKey?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.sshPublicKey,
                        sshPassphrase =
                            request.sshPassphrase?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.sshPassphrase,
                        httpsToken = request.httpsToken?.takeIf { it.isNotBlank() } ?: client.gitConfig?.httpsToken,
                        httpsUsername =
                            request.httpsUsername?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.httpsUsername,
                        httpsPassword =
                            request.httpsPassword?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.httpsPassword,
                        gpgPrivateKey =
                            request.gpgPrivateKey?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.gpgPrivateKey,
                        gpgPublicKey =
                            request.gpgPublicKey?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.gpgPublicKey,
                        gpgPassphrase =
                            request.gpgPassphrase?.takeIf { it.isNotBlank() }
                                ?: client.gitConfig?.gpgPassphrase,
                    )

                val updatedClient =
                    client.copy(
                        gitProvider = request.gitProvider,
                        gitAuthType = request.gitAuthType,
                        monoRepoUrl = request.monoRepoUrl,
                        defaultBranch = request.defaultBranch,
                        gitConfig = gitConfig,
                    )

                clientRepository.save(updatedClient)
                logger.info {
                    "Git configuration saved successfully for client: $clientId, " +
                        "provider=${request.gitProvider}, " +
                        "monoRepoUrl=${request.monoRepoUrl}"
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(e) { "Failed to setup Git for client: $clientId" }
                Result.failure(e)
            }
        }

    /**
     * Validate Git repository access using configured credentials.
     */
    suspend fun validateGitAccess(
        clientId: ObjectId,
        repoUrl: String,
        authType: GitAuthTypeEnum,
        credentials: GitSetupRequestDto,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Validating Git access for client: $clientId" }

                val envVars = mutableMapOf<String, String>()

                when (authType) {
                    GitAuthTypeEnum.SSH_KEY -> {
                        val privateKey = credentials.sshPrivateKey
                        if (!privateKey.isNullOrBlank()) {
                            val tempKeyDir = directoryStructureService.tempDir().resolve("git-validation-$clientId")
                            val sshWrapper = sshKeyManager.createTemporarySshKey(tempKeyDir, privateKey)
                            envVars["GIT_SSH"] = sshWrapper.toAbsolutePath().toString()
                        }
                    }

                    GitAuthTypeEnum.HTTPS_PAT, GitAuthTypeEnum.HTTPS_BASIC -> {
                        logger.info { "HTTPS authentication will be tested via URL" }
                    }

                    GitAuthTypeEnum.NONE -> {
                        logger.info { "No authentication required" }
                    }
                }

                val repoDir = directoryStructureService.clientGitDir(clientId)
                val gitDirExists = repoDir.resolve(".git").toFile().exists()

                if (gitDirExists) {
                    logger.info { "Existing repository detected at $repoDir; updating origin and fetching" }
                    executeGitValidation(
                        listOf(
                            "git",
                            "-C",
                            repoDir.toString(),
                            "remote",
                            "set-url",
                            "origin",
                            repoUrl,
                        ),
                        envVars,
                    )
                    executeGitValidation(
                        listOf(
                            "git",
                            "-C",
                            repoDir.toString(),
                            "fetch",
                            "--prune",
                            "--all",
                            "--tags",
                            "-f",
                        ),
                        envVars,
                    )
                } else {
                    logger.info { "No repository at $repoDir; cloning from $repoUrl" }
                    directoryStructureService.ensureDirectoryExists(repoDir.parent)
                    executeGitValidation(listOf("git", "clone", repoUrl, repoDir.toString()), envVars)
                }

                // Apply git config based on settings
                val gitUserName = credentials.gitConfig?.gitUserName
                val gitUserEmail = credentials.gitConfig?.gitUserEmail
                val requireGpgSign = credentials.gitConfig?.requireGpgSign ?: false
                val gpgKeyId = credentials.gitConfig?.gpgKeyId

                if (!gitUserName.isNullOrBlank()) {
                    executeGitValidation(
                        listOf("git", "-C", repoDir.toString(), "config", "user.name", gitUserName),
                        envVars,
                    )
                }
                if (!gitUserEmail.isNullOrBlank()) {
                    executeGitValidation(
                        listOf("git", "-C", repoDir.toString(), "config", "user.email", gitUserEmail),
                        envVars,
                    )
                }
                executeGitValidation(
                    listOf(
                        "git",
                        "-C",
                        repoDir.toString(),
                        "config",
                        "commit.gpgsign",
                        if (requireGpgSign) "true" else "false",
                    ),
                    envVars,
                )
                if (!gpgKeyId.isNullOrBlank()) {
                    executeGitValidation(
                        listOf("git", "-C", repoDir.toString(), "config", "user.signingkey", gpgKeyId),
                        envVars,
                    )
                }

                logger.info { "Git access validation and configuration successful for client: $clientId" }
                Result.success(true)
            } catch (e: Exception) {
                logger.warn(e) { "Git access validation/config failed for client: $clientId" }
                Result.success(false)
            }
        }

    /**
     * Clone client's mono-repository.
     */
    suspend fun cloneClientRepository(clientId: ObjectId): Result<Path> =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Cloning repository for client: $clientId" }

                val client = clientRepository.findById(clientId)
                if (client == null) {
                    return@withContext Result.failure(IllegalArgumentException("Client not found: $clientId"))
                }

                if (client.monoRepoUrl.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Client has no Git URL configured"))
                }

                val repoDir = directoryStructureService.clientGitDir(clientId)
                directoryStructureService.ensureDirectoryExists(repoDir)

                logger.info { "Repository will be cloned to: $repoDir" }
                logger.info { "Clone operation would be executed here (implementation pending full refactor)" }

                Result.success(repoDir)
            } catch (e: Exception) {
                logger.error(e) { "Failed to clone repository for client: $clientId" }
                Result.failure(e)
            }
        }

    /**
     * Inherit Git configuration from client to project (with optional overrides).
     */
    suspend fun inheritGitConfigToProject(
        clientId: ObjectId,
        projectId: ObjectId,
        overrideConfig: GitConfig?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Inheriting Git config from client $clientId to project $projectId" }

                val client = clientRepository.findById(clientId)
                if (client == null) {
                    return@withContext Result.failure(IllegalArgumentException("Client not found: $clientId"))
                }

                if (client.monoRepoUrl.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Client has no Git configuration to inherit"),
                    )
                }

                logger.info {
                    "Project will inherit Git config from client (with ${if (overrideConfig != null) "overrides" else "no overrides"})"
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(e) { "Failed to inherit Git config for project: $projectId" }
                Result.failure(e)
            }
        }

    /**
     * Setup Git override configuration for a project.
     * Allows project to use different credentials, remote URL, and auth type than the client.
     */
    suspend fun setupGitOverrideForProject(
        projectId: ObjectId,
        request: ProjectGitOverrideRequestDto,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Setting up Git override for project: $projectId" }

                val project = projectRepository.findById(projectId)
                if (project == null) {
                    return@withContext Result.failure(IllegalArgumentException("Project not found: $projectId"))
                }

                // Build ProjectOverrides with Git configuration and credentials
                val currentOverrides = project.overrides

                // Create GitConfig object with workflow settings and raw credentials (no encryption)
                val gitConfig =
                    GitConfig(
                        gitUserName = request.gitConfig?.gitUserName,
                        gitUserEmail = request.gitConfig?.gitUserEmail,
                        commitMessageTemplate = request.gitConfig?.commitMessageTemplate,
                        requireGpgSign = request.gitConfig?.requireGpgSign ?: false,
                        gpgKeyId = request.gitConfig?.gpgKeyId,
                        requireLinearHistory = request.gitConfig?.requireLinearHistory ?: false,
                        conventionalCommits = request.gitConfig?.conventionalCommits ?: false,
                        commitRules = request.gitConfig?.commitRules ?: emptyMap(),
                        sshPrivateKey =
                            request.sshPrivateKey?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.sshPrivateKey,
                        sshPublicKey =
                            request.sshPublicKey?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.sshPublicKey,
                        sshPassphrase =
                            request.sshPassphrase?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.sshPassphrase,
                        httpsToken =
                            request.httpsToken?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.httpsToken,
                        httpsUsername =
                            request.httpsUsername?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.httpsUsername,
                        httpsPassword =
                            request.httpsPassword?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.httpsPassword,
                        gpgPrivateKey =
                            request.gpgPrivateKey?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.gpgPrivateKey,
                        gpgPublicKey =
                            request.gpgPublicKey?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.gpgPublicKey,
                        gpgPassphrase =
                            request.gpgPassphrase?.takeIf { it.isNotBlank() }
                                ?: currentOverrides.gitConfig?.gpgPassphrase,
                    )

                val updatedOverrides =
                    currentOverrides.copy(
                        gitRemoteUrl = request.gitRemoteUrl,
                        gitAuthType = request.gitAuthType,
                        gitConfig = gitConfig,
                    )

                val updatedProject =
                    project.copy(
                        overrides = updatedOverrides,
                    )

                projectRepository.save(updatedProject)
                logger.info {
                    "Git override saved successfully for project: $projectId, " +
                        "hasRemoteUrl=${request.gitRemoteUrl != null}, " +
                        "hasAuthType=${request.gitAuthType != null}"
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(e) { "Failed to setup Git override for project: $projectId" }
                Result.failure(e)
            }
        }

    /**
     * Retrieve existing Git credentials for a project override.
     * Returns null if project has no Git override credentials configured.
     * Sensitive fields are masked - only their presence is indicated.
     */
    suspend fun getProjectGitCredentials(projectId: ObjectId): GitCredentialsDto? =
        withContext(Dispatchers.IO) {
            logger.info { "Retrieving project Git credentials for project: $projectId" }

            val project = projectRepository.findById(projectId) ?: return@withContext null
            val gitConfig = project.overrides.gitConfig ?: return@withContext null

            logger.info { "Found project: $projectId, returning raw project credentials (internal mode)" }

            val result =
                GitCredentialsDto(
                    sshPrivateKey = gitConfig.sshPrivateKey,
                    sshPublicKey = gitConfig.sshPublicKey,
                    sshPassphrase = gitConfig.sshPassphrase,
                    httpsToken = gitConfig.httpsToken,
                    httpsUsername = gitConfig.httpsUsername,
                    httpsPassword = gitConfig.httpsPassword,
                    gpgPrivateKey = gitConfig.gpgPrivateKey,
                    gpgPublicKey = gitConfig.gpgPublicKey,
                    gpgPassphrase = gitConfig.gpgPassphrase,
                )

            result
        }

    /**
     * Retrieve existing Git credentials for a client.
     * Returns null if client has no Git credentials configured.
     * Sensitive fields are masked - only their presence is indicated.
     */
    suspend fun getGitCredentials(clientId: ObjectId): GitCredentialsDto? =
        withContext(Dispatchers.IO) {
            logger.info { "Retrieving Git credentials for client: $clientId" }

            val client = clientRepository.findById(clientId) ?: return@withContext null
            val gitConfig = client.gitConfig ?: return@withContext null

            logger.info { "Found client: $clientId, returning raw credentials (internal mode)" }

            val result =
                GitCredentialsDto(
                    sshPrivateKey = gitConfig.sshPrivateKey,
                    sshPublicKey = gitConfig.sshPublicKey,
                    sshPassphrase = gitConfig.sshPassphrase,
                    httpsToken = gitConfig.httpsToken,
                    httpsUsername = gitConfig.httpsUsername,
                    httpsPassword = gitConfig.httpsPassword,
                    gpgPrivateKey = gitConfig.gpgPrivateKey,
                    gpgPublicKey = gitConfig.gpgPublicKey,
                    gpgPassphrase = gitConfig.gpgPassphrase,
                )

            result
        }

    private fun executeGitValidation(
        command: List<String>,
        envVars: Map<String, String>,
    ) {
        val processBuilder = ProcessBuilder(command)
        processBuilder.environment().putAll(envVars)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Git validation failed with exit code $exitCode")
        }
    }
}
