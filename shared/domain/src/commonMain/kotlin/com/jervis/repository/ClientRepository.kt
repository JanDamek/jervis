package com.jervis.repository

import com.jervis.dto.ClientDto
import com.jervis.service.IClientService

/**
 * Repository for Client operations
 * Wraps IClientService with additional logic (caching, error handling, etc.)
 */
class ClientRepository(
    private val clientService: IClientService
) {

    /**
     * List all clients
     */
    suspend fun listClients(): List<ClientDto> {
        return clientService.getAllClients()
    }

    /**
     * Get client by ID
     */
    suspend fun getClientById(id: String): ClientDto? {
        return clientService.getClientById(id)
    }

    /**
     * Create new client
     */
    suspend fun createClient(client: ClientDto): ClientDto {
        return clientService.createClient(client)
    }

    /**
     * Update existing client
     */
    suspend fun updateClient(id: String, client: ClientDto): ClientDto {
        return clientService.updateClient(id, client)
    }

    /**
     * Delete client
     */
    suspend fun deleteClient(id: String) {
        clientService.deleteClient(id)
    }

    /**
     * Update last selected project for client
     */
    suspend fun updateLastSelectedProject(clientId: String, projectId: String?): ClientDto {
        return clientService.updateLastSelectedProject(clientId, projectId)
    }
}
