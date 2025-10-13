package com.jervis.service.client

import com.jervis.dto.ClientDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.service.IClientService
import kotlinx.coroutines.flow.map
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

    override suspend fun create(client: ClientDto): ClientDto {
        val document = client.toDocument()
        val toSave = document.copy(updatedAt = Instant.now())
        val saved = clientRepository.save(toSave)
        logger.info { "Created client ${saved.name}" }
        return saved.toDto()
    }

    override suspend fun update(
        id: ObjectId,
        client: ClientDto,
    ): ClientDto {
        val existing = clientRepository.findById(id) ?: throw NoSuchElementException("Client not found")
        val document = client.toDocument()
        val merged = document.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = Instant.now())
        return clientRepository.save(merged).toDto()
    }

    override suspend fun delete(id: ObjectId) {
        val existing = clientRepository.findById(id) ?: return
        clientRepository.delete(existing)
        logger.info { "Deleted client ${existing.name}" }
    }

    override suspend fun list(): List<ClientDto> = clientRepository.findAll().map { it.toDto() }.toList()

    override suspend fun getClientById(id: ObjectId): ClientDto? = clientRepository.findById(id)?.toDto()
}
