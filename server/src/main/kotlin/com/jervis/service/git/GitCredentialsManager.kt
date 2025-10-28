package com.jervis.service.git

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

    suspend fun prepareGitAuthentication(project: ProjectDocument): GitAuthContext? =
        withContext(Dispatchers.IO) {
            try {
                val client = clientMongoRepository.findById(project.clientId)
                if (client == null) {
                    logger.error { "Client not found for project ${project.name}" }
                    return@withContext null
                }

                val clientGitConfig = client.gitConfig
                // TODO: Project overrides removed - git config now only at client level
                val gitConfig = clientGitConfig

                if (gitConfig == null) {
                    logger.info { "No Git configuration found for project ${project.name}" }
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
                        val clientGitConfig = client.gitConfig
                        // TODO: Project overrides removed - git config now only at client level
                        val gitConfig = clientGitConfig

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
}
