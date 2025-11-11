package com.jervis.controller.api

import com.jervis.dto.ClientDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IClientService
import com.jervis.service.client.ClientService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/clients")
class ClientRestController(
    private val clientService: ClientService,
) : IClientService {

    @GetMapping
    override suspend fun getAllClients(): List<ClientDto> = clientService.list().map { it.toDto() }

    @GetMapping("/{id}")
    override suspend fun getClientById(@PathVariable id: String): ClientDto? = clientService.getClientById(ObjectId(id))?.toDto()

    @PostMapping
    override suspend fun createClient(@RequestBody client: ClientDto): ClientDto = clientService.create(client.toDocument()).toDto()

    @PutMapping("/{id}")
    override suspend fun updateClient(
        @PathVariable id: String,
        @RequestBody client: ClientDto
    ): ClientDto {
        val updatedClient = client.copy(id = id)
        return clientService.update(updatedClient.toDocument()).toDto()
    }

    @DeleteMapping("/{id}")
    override suspend fun deleteClient(@PathVariable id: String) {
        clientService.delete(ObjectId(id))
    }

    @PatchMapping("/{id}/last-selected-project")
    override suspend fun updateLastSelectedProject(
        @PathVariable id: String,
        @RequestParam projectId: String?
    ): ClientDto {
        val client = clientService.getClientById(ObjectId(id))
            ?: throw IllegalArgumentException("Client not found")
        val updated = client.copy(lastSelectedProjectId = projectId?.let { ObjectId(it) })
        return clientService.update(updated).toDto()
    }
}
