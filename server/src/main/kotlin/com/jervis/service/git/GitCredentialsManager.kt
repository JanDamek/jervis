package com.jervis.service.git

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Orchestrates Git credential management.
 * Determines authentication type and delegates to appropriate managers (SSH/GPG/PAT).
 *
 * Supports both:
 * - Standalone project credentials (project.overrides.gitConfig or client.gitConfig)
 * - Mono-repo credentials (monoRepoConfig.credentialsOverride or client.gitConfig)
 */
@Service
class GitCredentialsManager(
    private val clientMongoRepository: ClientMongoRepository,
    private val sshKeyManager: SshKeyManager,
    private val gpgKeyManager: GpgKeyManager,
) {
    private val logger = KotlinLogging.logger {}

    data class GitAuthContext(
        val sshWrapperPath: Path? = null,
        val gpgKeyId: String? = null,
        val decryptedHttpsToken: String? = null,
        val httpsUsername: String? = null,
        val httpsPassword: String? = null,
    )

    /**
     * Prepare Git authentication context for a project following credential precedence:
     * 1. Project Override (project.overrides.gitConfig) - highest priority
     * 2. Client Global (client.gitConfig) - default credentials
     * 3. None - public repository, no authentication required
     *
     * Supports SSH key authentication and HTTPS (username/password or token).
     */
    suspend fun prepareGitAuthentication(project: ProjectDocument): GitAuthContext? =
        withContext(Dispatchers.IO) {
            try {
                val client = clientMongoRepository.findById(project.clientId)
                if (client == null) {
                    logger.error { "Client not found for project ${project.name}" }
                    return@withContext null
                }

                // Priority 1: Project-specific Git credentials override
                val gitConfig = project.overrides?.gitConfig ?: client.gitConfig

                when {
                    project.overrides?.gitConfig != null ->
                        logger.info { "Using project-specific Git credentials for ${project.name}" }

                    client.gitConfig != null ->
                        logger.info { "Using client global Git credentials for ${project.name}" }

                    else -> {
                        logger.info { "No Git credentials configured for project ${project.name} - assuming public repository" }
                        return@withContext null
                    }
                }

                if (gitConfig == null) {
                    return@withContext null
                }

                // Check if any credentials are configured
                val hasCredentials =
                    gitConfig.sshPrivateKey != null ||
                        gitConfig.httpsToken != null ||
                        gitConfig.httpsPassword != null

                if (!hasCredentials) {
                    logger.info { "No Git credentials found for project ${project.name}, assuming public repository" }
                    return@withContext null
                }

                val sshWrapperPath = prepareSSH(project, gitConfig)
                val gpgKeyId = prepareGPG(gitConfig)

                // HTTPS credentials if present
                val decryptedHttpsToken = gitConfig.httpsToken
                val httpsUsername = gitConfig.httpsUsername
                val decryptedHttpsPassword = gitConfig.httpsPassword

                GitAuthContext(
                    sshWrapperPath = sshWrapperPath,
                    gpgKeyId = gpgKeyId,
                    decryptedHttpsToken = decryptedHttpsToken,
                    httpsUsername = httpsUsername,
                    httpsPassword = decryptedHttpsPassword,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to prepare Git authentication for project ${project.name}" }
                null
            }
        }

    private suspend fun prepareSSH(
        project: ProjectDocument,
        gitConfig: com.jervis.domain.git.GitConfig,
    ): Path? {
        val sshPrivateKey = gitConfig.sshPrivateKey
        if (sshPrivateKey.isNullOrBlank()) {
            return null
        }

        return try {
            val publicKey = gitConfig.sshPublicKey

            val sshConfigPath =
                sshKeyManager.prepareSshAuthenticationWithKey(
                    project = project,
                    decryptedPrivateKey = sshPrivateKey,
                    publicKey = publicKey,
                )

            if (sshConfigPath != null) {
                val keyDir = sshConfigPath.parent
                sshKeyManager.createSshWrapper(sshConfigPath, keyDir)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to prepare SSH authentication for project ${project.name}" }
            null
        }
    }

    private suspend fun prepareGPG(gitConfig: com.jervis.domain.git.GitConfig): String? = gitConfig.gpgKeyId

    suspend fun configureGitRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitAuthContext?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                if (authContext?.gpgKeyId != null) {
                    val client = clientMongoRepository.findById(project.clientId)
                    if (client != null) {
                        // Priority 1: Project-specific Git config, Priority 2: Client global Git config
                        val gitConfig = project.overrides?.gitConfig ?: client.gitConfig

                        if (gitConfig != null) {
                            gitConfig.gpgPrivateKey
                            // Optionally, the private key can be imported before enabling signing (not handled here)
                            gpgKeyManager.configureGitGpgSigning(
                                gitDir = gitDir,
                                keyId = authContext.gpgKeyId,
                                userName = gitConfig.gitUserName,
                                userEmail = gitConfig.gitUserEmail,
                            )
                        }
                    }
                }

                logger.info { "Configured Git repository at $gitDir for project ${project.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to configure Git repository" }
            }
        }
    }

    // ========== Mono-Repo Methods ==========

    /**
     * Prepare Git authentication context for mono-repo following credential precedence:
     * 1. Mono-repo credentials override (monoRepoConfig.credentialsOverride) - highest priority
     * 2. Client global credentials (client.gitConfig) - default credentials
     * 3. None - public repository, no authentication required
     */
    suspend fun prepareMonoRepoAuthentication(
        client: ClientDocument,
        monoRepoConfig: MonoRepoConfig,
    ): GitAuthContext? =
        withContext(Dispatchers.IO) {
            try {
                // Priority 1: Mono-repo specific credentials override
                val gitConfig = monoRepoConfig.credentialsOverride ?: client.gitConfig

                when {
                    monoRepoConfig.credentialsOverride != null ->
                        logger.info { "Using mono-repo specific credentials for ${monoRepoConfig.name}" }

                    client.gitConfig != null ->
                        logger.info { "Using client global credentials for mono-repo ${monoRepoConfig.name}" }

                    else -> {
                        logger.info { "No credentials configured for mono-repo ${monoRepoConfig.name} - assuming public repository" }
                        return@withContext null
                    }
                }

                if (gitConfig == null) {
                    return@withContext null
                }

                // Check if any credentials are configured
                val hasCredentials =
                    gitConfig.sshPrivateKey != null ||
                        gitConfig.httpsToken != null ||
                        gitConfig.httpsPassword != null

                if (!hasCredentials) {
                    logger.info { "No credentials found for mono-repo ${monoRepoConfig.name}, assuming public repository" }
                    return@withContext null
                }

                val sshWrapperPath = prepareMonoRepoSSH(client, monoRepoConfig, gitConfig)
                val gpgKeyId = prepareGPG(gitConfig)

                // HTTPS credentials if present
                val decryptedHttpsToken = gitConfig.httpsToken
                val httpsUsername = gitConfig.httpsUsername
                val decryptedHttpsPassword = gitConfig.httpsPassword

                GitAuthContext(
                    sshWrapperPath = sshWrapperPath,
                    gpgKeyId = gpgKeyId,
                    decryptedHttpsToken = decryptedHttpsToken,
                    httpsUsername = httpsUsername,
                    httpsPassword = decryptedHttpsPassword,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to prepare mono-repo authentication for ${monoRepoConfig.name}" }
                null
            }
        }

    private suspend fun prepareMonoRepoSSH(
        client: ClientDocument,
        monoRepoConfig: MonoRepoConfig,
        gitConfig: com.jervis.domain.git.GitConfig,
    ): Path? {
        val sshPrivateKey = gitConfig.sshPrivateKey
        if (sshPrivateKey.isNullOrBlank()) {
            return null
        }

        return try {
            val publicKey = gitConfig.sshPublicKey

            // Use client-specific SSH key location for mono-repos
            val sshConfigPath =
                sshKeyManager.prepareMonoRepoSshAuthentication(
                    client = client,
                    monoRepoId = monoRepoConfig.id,
                    decryptedPrivateKey = sshPrivateKey,
                    publicKey = publicKey,
                )

            if (sshConfigPath != null) {
                val keyDir = sshConfigPath.parent
                sshKeyManager.createSshWrapper(sshConfigPath, keyDir)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to prepare SSH authentication for mono-repo ${monoRepoConfig.name}" }
            null
        }
    }

    suspend fun configureMonoRepoGit(
        client: ClientDocument,
        monoRepoConfig: MonoRepoConfig,
        gitDir: Path,
        authContext: GitAuthContext?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                if (authContext?.gpgKeyId != null) {
                    // Priority 1: Mono-repo specific Git config, Priority 2: Client global Git config
                    val gitConfig = monoRepoConfig.credentialsOverride ?: client.gitConfig

                    if (gitConfig != null) {
                        gpgKeyManager.configureGitGpgSigning(
                            gitDir = gitDir,
                            keyId = authContext.gpgKeyId,
                            userName = gitConfig.gitUserName,
                            userEmail = gitConfig.gitUserEmail,
                        )
                    }
                }

                logger.info { "Configured mono-repo Git repository at $gitDir for ${monoRepoConfig.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to configure mono-repo Git repository" }
            }
        }
    }
}
