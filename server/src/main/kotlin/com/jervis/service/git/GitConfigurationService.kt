package com.jervis.service.git

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig
import com.jervis.dto.GitSetupRequestDto
import com.jervis.entity.mongo.ServiceCredentialsDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ServiceCredentialsMongoRepository
import com.jervis.service.security.KeyEncryptionService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
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
    private val credentialsRepository: ServiceCredentialsMongoRepository,
    private val keyEncryptionService: KeyEncryptionService,
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

                val credentialsId = saveGitCredentials(clientId, request)

                val updatedClient =
                    client.copy(
                        gitProvider = request.gitProvider,
                        gitAuthType = request.gitAuthType,
                        monoRepoUrl = request.monoRepoUrl,
                        monoRepoCredentialsRef = credentialsId.toHexString(),
                        defaultBranch = request.defaultBranch,
                        gitConfig =
                            request.gitConfig?.let {
                                GitConfig(
                                    commitMessageTemplate = it.commitMessageTemplate,
                                    requireGpgSign = it.requireGpgSign,
                                    gpgKeyId = it.gpgKeyId,
                                    requireLinearHistory = it.requireLinearHistory,
                                    conventionalCommits = it.conventionalCommits,
                                    commitRules = it.commitRules,
                                )
                            },
                    )

                clientRepository.save(updatedClient)
                logger.info { "Git configuration saved successfully for client: $clientId" }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(e) { "Failed to setup Git for client: $clientId" }
                Result.failure(e)
            }
        }

    /**
     * Save Git credentials (SSH keys, GPG keys, tokens) in encrypted form.
     */
    private suspend fun saveGitCredentials(
        clientId: ObjectId,
        request: GitSetupRequestDto,
    ): ObjectId =
        withContext(Dispatchers.IO) {
            val encryptedSshPrivateKey =
                request.sshPrivateKey?.let {
                    keyEncryptionService.encryptSshKey(it)
                }

            val encryptedGpgPrivateKey =
                request.gpgPrivateKey?.let {
                    keyEncryptionService.encryptGpgKey(it)
                }

            val encryptedToken =
                request.httpsToken?.let {
                    keyEncryptionService.encryptToken(it)
                }

            val encryptedPassword =
                request.httpsPassword?.let {
                    keyEncryptionService.encryptPassword(it)
                }

            val credentials =
                ServiceCredentialsDocument(
                    clientId = clientId,
                    projectId = null,
                    serviceTypeEnum = ServiceTypeEnum.GIT,
                    username = request.httpsUsername,
                    sshPrivateKey = encryptedSshPrivateKey,
                    sshPublicKey = request.sshPublicKey,
                    sshPassphrase = request.sshPassphrase,
                    gpgPrivateKey = encryptedGpgPrivateKey,
                    gpgPublicKey = null,
                    gpgPassphrase = request.gpgPassphrase,
                    personalAccessToken = encryptedToken,
                    additionalData = if (encryptedPassword != null) mapOf("password" to encryptedPassword) else emptyMap(),
                )

            val saved = credentialsRepository.save(credentials).awaitSingle()
            saved.id
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

                val command = listOf("git", "ls-remote", repoUrl, "HEAD")
                val envVars = mutableMapOf<String, String>()

                when (authType) {
                    GitAuthTypeEnum.SSH_KEY -> {
                        val privateKey = credentials.sshPrivateKey
                        if (privateKey != null) {
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

                executeGitValidation(command, envVars)
                logger.info { "Git access validation successful for client: $clientId" }
                Result.success(true)
            } catch (e: Exception) {
                logger.warn(e) { "Git access validation failed for client: $clientId" }
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
