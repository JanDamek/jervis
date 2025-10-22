package com.jervis.service.client

import com.jervis.entity.mongo.ClientDocument
import com.jervis.repository.mongo.ClientMongoRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClientService(
    private val clientRepository: ClientMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun create(clientName: String): ClientDocument {
        val document = ClientDocument(name = clientName)
        val saved = clientRepository.save(document)
        logger.info { "Created client ${saved.name}" }
        return saved
    }

    suspend fun create(client: ClientDocument): ClientDocument {
        val newClient = client.copy(id = ObjectId.get(), createdAt = Instant.now(), updatedAt = Instant.now())
        val saved = clientRepository.save(newClient)
        logger.info { "Created client ${saved.name} with id ${saved.id}" }
        return saved
    }

    suspend fun update(client: ClientDocument): ClientDocument {
        val existing =
            clientRepository.findById(client.id) ?: throw NoSuchElementException("Client not found: ${client.id}")

        val mergedGitConfig =
            when {
                client.gitConfig != null && existing.gitConfig != null -> {
                    existing.gitConfig.copy(
                        gitUserName = client.gitConfig.gitUserName ?: existing.gitConfig.gitUserName,
                        gitUserEmail = client.gitConfig.gitUserEmail ?: existing.gitConfig.gitUserEmail,
                        commitMessageTemplate =
                            client.gitConfig.commitMessageTemplate
                                ?: existing.gitConfig.commitMessageTemplate,
                        requireGpgSign = client.gitConfig.requireGpgSign,
                        gpgKeyId = client.gitConfig.gpgKeyId ?: existing.gitConfig.gpgKeyId,
                        requireLinearHistory = client.gitConfig.requireLinearHistory,
                        conventionalCommits = client.gitConfig.conventionalCommits,
                        commitRules =
                            if (client.gitConfig.commitRules.isNotEmpty()) {
                                client.gitConfig.commitRules
                            } else {
                                existing.gitConfig.commitRules
                            },
                        sshPrivateKey = client.gitConfig.sshPrivateKey ?: existing.gitConfig.sshPrivateKey,
                        sshPublicKey = client.gitConfig.sshPublicKey ?: existing.gitConfig.sshPublicKey,
                        sshPassphrase = client.gitConfig.sshPassphrase ?: existing.gitConfig.sshPassphrase,
                        httpsToken = client.gitConfig.httpsToken ?: existing.gitConfig.httpsToken,
                        httpsUsername = client.gitConfig.httpsUsername ?: existing.gitConfig.httpsUsername,
                        httpsPassword = client.gitConfig.httpsPassword ?: existing.gitConfig.httpsPassword,
                        gpgPrivateKey = client.gitConfig.gpgPrivateKey ?: existing.gitConfig.gpgPrivateKey,
                        gpgPublicKey = client.gitConfig.gpgPublicKey ?: existing.gitConfig.gpgPublicKey,
                        gpgPassphrase = client.gitConfig.gpgPassphrase ?: existing.gitConfig.gpgPassphrase,
                    )
                }

                client.gitConfig != null -> client.gitConfig
                else -> existing.gitConfig
            }

        val merged =
            existing.copy(
                name = client.name,
                gitProvider = client.gitProvider,
                gitAuthType = client.gitAuthType,
                monoRepoUrl = client.monoRepoUrl,
                defaultBranch = client.defaultBranch,
                gitConfig = mergedGitConfig,
                description = client.description,
                shortDescription = client.shortDescription,
                fullDescription = client.fullDescription,
                defaultCodingGuidelines = client.defaultCodingGuidelines,
                defaultReviewPolicy = client.defaultReviewPolicy,
                defaultFormatting = client.defaultFormatting,
                defaultSecretsPolicy = client.defaultSecretsPolicy,
                defaultAnonymization = client.defaultAnonymization,
                defaultInspirationPolicy = client.defaultInspirationPolicy,
                defaultLanguageEnum = client.defaultLanguageEnum,
                audioPath = client.audioPath,
                dependsOnProjects = client.dependsOnProjects,
                isDisabled = client.isDisabled,
                disabledProjects = client.disabledProjects,
                updatedAt = Instant.now(),
            )

        val updated = clientRepository.save(merged)
        logger.info { "Updated client ${updated.name}" }
        return updated
    }

    suspend fun delete(id: ObjectId) {
        val existing =
            clientRepository.findById(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    suspend fun list(): List<ClientDocument> = clientRepository.findAll().map { it }.toList()

    suspend fun getClientById(id: ObjectId): ClientDocument? = clientRepository.findById(id)
}
