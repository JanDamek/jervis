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
    private val slugRegex = Regex("^[a-z0-9-]+$")

    suspend fun create(client: ClientDocument): ClientDocument {
        validateClient(client)
        val toSave = client.copy(updatedAt = Instant.now())
        val saved = clientRepository.save(toSave)
        logger.info { "Created client ${saved.name} (${saved.slug})" }
        return saved
    }

    suspend fun update(id: ObjectId, client: ClientDocument): ClientDocument {
        validateClient(client)
        val existing = clientRepository.findById(id.toString()) ?: throw NoSuchElementException("Client not found")
        val merged = client.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged)
    }

    suspend fun delete(id: ObjectId) {
        val existing = clientRepository.findById(id.toString()) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name} (${existing.slug})" }
    }

    suspend fun list(): List<ClientDocument> = clientRepository.findAll().toList()

    suspend fun get(id: ObjectId): ClientDocument? = clientRepository.findById(id.toString())

    private fun validateClient(client: ClientDocument) {
        require(client.slug.matches(slugRegex)) { "Invalid slug: must match [a-z0-9-]+" }
        // Additional minimal checks can be added later
    }
}
