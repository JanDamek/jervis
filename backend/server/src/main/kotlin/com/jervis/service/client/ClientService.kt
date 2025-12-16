package com.jervis.service.client

import com.jervis.entity.ClientDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.rag.internal.WeaviatePerClientProvisioner
import com.jervis.repository.ClientMongoRepository
import com.jervis.types.ClientId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ClientService(
    private val clientRepository: ClientMongoRepository,
    private val graphDBService: GraphDBService,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun create(clientName: String): ClientDocument {
        val document = ClientDocument(name = clientName)
        val saved = clientRepository.save(document)
        logger.info { "Created client ${saved.name}" }

        // Initialize ArangoDB graph schema and Weaviate collections for new client
        try {
            val graphStatus = graphDBService.ensureSchema(saved.id)
            weaviateProvisioner.ensureClientCollections(saved.id)

            if (graphStatus.ok) {
                logger.info { "Initialized graph schema for client ${saved.name}" }
            } else {
                logger.warn { "Graph schema initialization had warnings for ${saved.name}: ${graphStatus.warnings}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize graph/RAG for client ${saved.name}" }
            // Continue - client is created, schema can be initialized later
        }

        return saved
    }

    suspend fun create(client: ClientDocument): ClientDocument {
        val newClient = client.copy(id = ClientId(ObjectId.get()))
        val saved = clientRepository.save(newClient)
        logger.info { "Created client ${saved.name} with id ${saved.id}" }

        // Initialize ArangoDB graph schema and Weaviate collections for new client
        try {
            val graphStatus = graphDBService.ensureSchema(saved.id)
            weaviateProvisioner.ensureClientCollections(saved.id)

            if (graphStatus.ok) {
                logger.info { "Initialized graph schema for client ${saved.name}" }
            } else {
                logger.warn { "Graph schema initialization had warnings for ${saved.name}: ${graphStatus.warnings}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize graph/RAG for client ${saved.name}" }
            // Continue - client is created, schema can be initialized later
        }

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
                            client.gitConfig.commitRules.ifEmpty {
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

                client.gitConfig != null -> {
                    client.gitConfig
                }

                else -> {
                    existing.gitConfig
                }
            }

        val merged =
            existing.copy(
                name = client.name,
                gitProvider = client.gitProvider,
                gitAuthType = client.gitAuthType,
                gitConfig = mergedGitConfig,
                description = client.description,
                defaultLanguageEnum = client.defaultLanguageEnum,
                lastSelectedProjectId = client.lastSelectedProjectId,
                connectionIds = client.connectionIds,
            )

        val updated = clientRepository.save(merged)
        logger.info { "Updated client ${updated.name}" }
        return updated
    }

    suspend fun delete(id: ClientId) {
        val existing = clientRepository.findById(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    suspend fun list(): List<ClientDocument> = clientRepository.findAll().toList()

    suspend fun getClientById(id: ClientId): ClientDocument? = clientRepository.findById(id)
}
