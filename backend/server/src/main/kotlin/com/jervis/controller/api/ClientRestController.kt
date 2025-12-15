package com.jervis.controller.api

import com.jervis.dto.ClientDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IClientService
import com.jervis.service.client.ClientService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/clients")
class ClientRestController(
    private val clientService: ClientService,
) : IClientService {
    @GetMapping
    override suspend fun getAllClients(): List<ClientDto> = clientService.list().map { it.toDto() }

    @GetMapping("/{id}")
    override suspend fun getClientById(
        @PathVariable id: String,
    ): ClientDto? = clientService.getClientById(ClientId(ObjectId(id)))?.toDto()

    @PostMapping
    override suspend fun createClient(
        @RequestBody client: ClientDto,
    ): ClientDto = clientService.create(client.toDocument()).toDto()

    @PutMapping("/{id}")
    override suspend fun updateClient(
        @PathVariable id: String,
        @RequestBody client: ClientDto,
    ): ClientDto {
        try {
            // Validate that id is a valid ObjectId
            ObjectId(id)
            val updatedClient = client.copy(id = id)
            return clientService.update(updatedClient.toDocument()).toDto()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid client ID format: $id", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to update client: ${e.message}", e)
        }
    }

    @DeleteMapping("/{id}")
    override suspend fun deleteClient(
        @PathVariable id: String,
    ) {
        clientService.delete(ClientId(ObjectId(id)))
    }

    @PatchMapping("/{id}/last-selected-project")
    override suspend fun updateLastSelectedProject(
        @PathVariable id: String,
        @RequestParam projectId: String?,
    ): ClientDto {
        val client =
            clientService.getClientById(ClientId(ObjectId(id)))
                ?: throw IllegalArgumentException("Client not found")
        val updated = client.copy(lastSelectedProjectId = projectId?.let { ProjectId(ObjectId(it)) })
        return clientService.update(updated).toDto()
    }
}
