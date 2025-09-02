package com.jervis.service.client

import com.jervis.entity.mongo.ClientDocument
import com.jervis.repository.mongo.ClientMongoRepository
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

    suspend fun create(client: ClientDocument): ClientDocument {
        val toSave = client.copy(updatedAt = Instant.now())
        val saved = clientRepository.save(toSave)
        logger.info { "Created client ${saved.name}" }
        return saved
    }

    suspend fun update(
        id: ObjectId,
        client: ClientDocument,
    ): ClientDocument {
        val existing = clientRepository.findById(id) ?: throw NoSuchElementException("Client not found")
        val merged = client.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged)
    }

    suspend fun delete(id: ObjectId) {
        val existing = clientRepository.findById(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    suspend fun list(): List<ClientDocument> = clientRepository.findAll().toList()

    suspend fun getClientById(id: ObjectId): ClientDocument? = clientRepository.findById(id)
}
