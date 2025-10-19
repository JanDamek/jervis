package com.jervis.controller.api

import com.jervis.dto.ClientDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IClientService
import com.jervis.service.client.ClientService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController

@RestController
class ClientRestController(
    private val clientService: ClientService,
) : IClientService {
    override suspend fun createByName(clientName: String): ClientDto = clientService.create(clientName).toDto()

    override suspend fun create(client: ClientDto): ClientDto = clientService.create(client.toDocument()).toDto()

    override suspend fun update(client: ClientDto): ClientDto = clientService.update(client.toDocument()).toDto()

    override suspend fun delete(id: String) {
        clientService.delete(ObjectId(id))
    }

    override suspend fun list(): List<ClientDto> = clientService.list().map { it.toDto() }

    override suspend fun getClientById(id: String): ClientDto? = clientService.getClientById(ObjectId(id))?.toDto()
}
