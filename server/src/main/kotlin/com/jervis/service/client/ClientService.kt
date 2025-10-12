package com.jervis.service.client

import com.jervis.entity.mongo.ClientDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.service.IClientService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClientService(
    private val clientRepository: ClientMongoRepository,
) : IClientService {
    private val logger = KotlinLogging.logger {}

    override suspend fun create(client: ClientDocument): ClientDocument {
        val toSave = client.copy(updatedAt = Instant.now())
        val saved = clientRepository.save(toSave)
        logger.info { "Created client ${saved.name}" }
        return saved
    }

    override suspend fun update(
        id: ObjectId,
        client: ClientDocument,
    ): ClientDocument {
        val existing = clientRepository.findById(id) ?: throw NoSuchElementException("Client not found")
        val merged = client.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged)
    }

    override suspend fun delete(id: ObjectId) {
        val existing = clientRepository.findById(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    override suspend fun list(): List<ClientDocument> = clientRepository.findAll().toList()

    override suspend fun getClientById(id: ObjectId): ClientDocument? = clientRepository.findById(id)
}
