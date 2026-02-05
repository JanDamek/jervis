package com.jervis.service.client

import com.jervis.common.types.ClientId
import com.jervis.entity.ClientDocument
import com.jervis.repository.ClientRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ClientService(
    private val clientRepository: ClientRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun create(clientName: String): ClientDocument {
        val document = ClientDocument(name = clientName)
        val saved = clientRepository.save(document)
        logger.info { "Created client ${saved.name}" }
        return saved
    }

    suspend fun create(client: ClientDocument): ClientDocument {
        val newClient = client.copy(id = ClientId(ObjectId.get()))
        val saved = clientRepository.save(newClient)
        logger.info { "Created client ${saved.name} with id ${saved.id}" }
        return saved
    }

    suspend fun update(client: ClientDocument): ClientDocument {
        val existing =
            getClientByIdOrNull(client.id) ?: throw NoSuchElementException("Client not found: ${client.id}")

        val merged =
            existing.copy(
                name = client.name,
                description = client.description,
                defaultLanguageEnum = client.defaultLanguageEnum,
                lastSelectedProjectId = client.lastSelectedProjectId,
                connectionIds = client.connectionIds,
                connectionCapabilities = client.connectionCapabilities,
                gitCommitConfig = client.gitCommitConfig,
            )

        val updated = clientRepository.save(merged)
        logger.info { "Updated client ${updated.name}" }
        return updated
    }

    suspend fun delete(id: ClientId) {
        val existing = getClientByIdOrNull(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    suspend fun list(): List<ClientDocument> = clientRepository.findAll().toList()

    suspend fun getClientById(id: ClientId): ClientDocument = getClientByIdOrNull(id) ?: throw ClientNotFoundException(id)

    suspend fun getClientByIdOrNull(id: ClientId): ClientDocument? = clientRepository.findAll().toList().find { it.id == id }
}

class ClientNotFoundException(
    clientId: ClientId,
) : Exception("Client not found: $clientId")
