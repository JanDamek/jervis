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
        val saved = clientRepository.save(client)
        logger.info { "Created client ${saved.name}" }
        return saved
    }

    suspend fun update(client: ClientDocument): ClientDocument {
        val existing =
            clientRepository.findById(client.id) ?: throw NoSuchElementException("Client not found")
        val merged = client.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged)
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
