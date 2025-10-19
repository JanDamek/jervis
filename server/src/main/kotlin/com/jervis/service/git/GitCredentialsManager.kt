package com.jervis.service.git

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.entity.mongo.ServiceCredentialsDocument
import com.jervis.repository.mongo.ServiceCredentialsMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
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
    private val credentialsRepository: ServiceCredentialsMongoRepository,
    private val sshKeyManager: SshKeyManager,
    private val gpgKeyManager: GpgKeyManager,
) {
    private val logger = KotlinLogging.logger {}

    data class GitAuthContext(
        val sshWrapperPath: Path? = null,
        val gpgKeyId: String? = null,
        val personalAccessToken: String? = null,
        val username: String? = null,
    )

    suspend fun prepareGitAuthentication(project: ProjectDocument): GitAuthContext? =
        withContext(Dispatchers.IO) {
            try {
                val credentials = findCredentials(project)
                if (credentials == null) {
                    logger.info { "No Git credentials found for project ${project.name}, assuming public repository" }
                    return@withContext null
                }

                val sshWrapperPath = prepareSSH(project, credentials)
                val gpgKeyId = prepareGPG(project, credentials)

                GitAuthContext(
                    sshWrapperPath = sshWrapperPath,
                    gpgKeyId = gpgKeyId,
                    personalAccessToken = credentials.personalAccessToken,
                    username = credentials.username,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to prepare Git authentication for project ${project.name}" }
                null
            }
        }

    private suspend fun findCredentials(project: ProjectDocument): ServiceCredentialsDocument? =
        withContext(Dispatchers.IO) {
            // TODO: Fetch Client document and use client.monoRepoCredentialsRef
            credentialsRepository
                .findByClientIdAndProjectIdAndServiceTypeEnum(
                    clientId = project.clientId,
                    projectId = project.id,
                    serviceTypeEnum = ServiceTypeEnum.GIT,
                ).collectList()
                .awaitSingle()
                .firstOrNull()
        }

    private suspend fun prepareSSH(
        project: ProjectDocument,
        credentials: ServiceCredentialsDocument,
    ): Path? {
        if (credentials.sshPrivateKey.isNullOrBlank()) {
            return null
        }

        val sshConfigPath = sshKeyManager.prepareSshAuthentication(project, credentials)
        return if (sshConfigPath != null) {
            val keyDir = sshConfigPath.parent
            sshKeyManager.createSshWrapper(sshConfigPath, keyDir)
        } else {
            null
        }
    }

    private suspend fun prepareGPG(
        project: ProjectDocument,
        credentials: ServiceCredentialsDocument,
    ): String? {
        // TODO: Fetch Client document and use client.gitConfig
        return null
    }

    suspend fun configureGitRepository(
        project: ProjectDocument,
        gitDir: Path,
        authContext: GitAuthContext?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                // TODO: Fetch Client document and use client.gitConfig
                if (authContext?.gpgKeyId != null) {
                    val credentials = findCredentials(project)
                    gpgKeyManager.configureGitGpgSigning(
                        gitDir = gitDir,
                        keyId = authContext.gpgKeyId,
                        userName = credentials?.username,
                        userEmail = credentials?.additionalData?.get("email"),
                    )
                }

                logger.info { "Configured Git repository at $gitDir for project ${project.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to configure Git repository" }
            }
        }
    }
}
