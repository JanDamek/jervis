package com.jervis.service.client

import com.jervis.dto.ClientDto
import com.jervis.entity.mongo.ClientDocument
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
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

    suspend fun create(clientName: String): ClientDto {
        val document = ClientDocument(name = clientName)
        val saved = clientRepository.save(document)
        logger.info { "Created client ${saved.name}" }
        return saved.toDto()
    }

    suspend fun create(client: ClientDto): ClientDto {
        val saved = clientRepository.save(client.toDocument())
        logger.info { "Created client ${saved.name}" }
        return saved.toDto()
    }

    suspend fun update(client: ClientDto): ClientDto {
        val existing =
            clientRepository.findById(
                ObjectId(client.id),
            ) ?: throw NoSuchElementException("Client not found")
        val document = client.toDocument()
        val merged = document.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged).toDto()
    }

    suspend fun delete(id: String) {
        val existing =
            clientRepository.findById(
                ObjectId(id),
            ) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    suspend fun list(): List<ClientDto> = clientRepository.findAll().map { it.toDto() }.toList()

    suspend fun getClientById(id: String): ClientDto? =
        clientRepository
            .findById(
                ObjectId(id),
            )?.toDto()
}
