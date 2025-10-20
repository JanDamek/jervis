package com.jervis.service.git

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.service.security.KeyEncryptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Orchestrates Git credential management.
 * Determines authentication type and delegates to appropriate managers (SSH/GPG/PAT).
 */
@Service
class GitCredentialsManager(
    private val clientMongoRepository: ClientMongoRepository,
    private val keyEncryptionService: KeyEncryptionService,
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

    suspend fun prepareGitAuthentication(project: ProjectDocument): GitAuthContext? =
        withContext(Dispatchers.IO) {
            try {
                val client = clientMongoRepository.findById(project.clientId)
                if (client == null) {
                    logger.error { "Client not found for project ${project.name}" }
                    return@withContext null
                }

                val clientGitConfig = client.gitConfig
                val overrideGitConfig = project.overrides.gitConfig
                val gitConfig = overrideGitConfig ?: clientGitConfig

                if (gitConfig == null) {
                    logger.info { "No Git configuration found for project ${project.name}" }
                    return@withContext null
                }

                // Check if any credentials are configured
                val hasCredentials =
                    gitConfig.encryptedSshPrivateKey != null ||
                        gitConfig.encryptedHttpsToken != null

                if (!hasCredentials) {
                    logger.info { "No Git credentials found for project ${project.name}, assuming public repository" }
                    return@withContext null
                }

                val sshWrapperPath = prepareSSH(project, gitConfig)
                val gpgKeyId = prepareGPG(gitConfig)

                // Decrypt HTTPS credentials if present
                val decryptedHttpsToken =
                    gitConfig.encryptedHttpsToken
                        ?.let { keyEncryptionService.decrypt(it) }
                val httpsUsername = gitConfig.httpsUsername
                val decryptedHttpsPassword =
                    gitConfig.encryptedHttpsPassword
                        ?.let { keyEncryptionService.decrypt(it) }

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
        val encryptedSshPrivateKey = gitConfig.encryptedSshPrivateKey
        if (encryptedSshPrivateKey.isNullOrBlank()) {
            return null
        }

        return try {
            // Decrypt the SSH private key
            val decryptedKey = keyEncryptionService.decrypt(encryptedSshPrivateKey)
            val publicKey = gitConfig.sshPublicKey

            // Prepare SSH authentication with decrypted key
            val sshConfigPath =
                sshKeyManager.prepareSshAuthenticationWithKey(
                    project = project,
                    decryptedPrivateKey = decryptedKey,
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
                        val clientGitConfig = client.gitConfig
                        val overrideGitConfig = project.overrides.gitConfig
                        val gitConfig = overrideGitConfig ?: clientGitConfig

                        if (gitConfig != null) {
                            // Get GPG private key from gitConfig
                            val encryptedGpgPrivateKey = gitConfig.encryptedGpgPrivateKey

                            encryptedGpgPrivateKey?.let {
                                keyEncryptionService.decrypt(it)
                            }

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
}
