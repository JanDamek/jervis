package com.jervis.repository

import com.jervis.dto.ClientDto
import com.jervis.service.IClientService

/**
 * Repository for Client operations
 * Wraps IClientService with error handling to prevent crashes
 */
class ClientRepository(
    private val clientService: IClientService
) : BaseRepository() {

    /**
     * List all clients
     */
    suspend fun listClients(): List<ClientDto> =
        safeRpcListCall("listClients") {
            clientService.getAllClients()
        }

    /**
     * Get client by ID
     */
    suspend fun getClientById(id: String): ClientDto? =
        safeRpcCall("getClientById", returnNull = true) {
            clientService.getClientById(id)
        }

    /**
     * Create new client
     */
    suspend fun createClient(client: ClientDto): ClientDto? =
        safeRpcCall("createClient", returnNull = true) {
            clientService.createClient(client)
        }

    /**
     * Update existing client
     */
    suspend fun updateClient(id: String, client: ClientDto): ClientDto? =
        safeRpcCall("updateClient", returnNull = true) {
            clientService.updateClient(id, client)
        }

    /**
     * Delete client
     */
    suspend fun deleteClient(id: String) {
        safeRpcCall("deleteClient", returnNull = true) {
            clientService.deleteClient(id)
        }
    }

    /**
     * Update last selected project for client
     */
    suspend fun updateLastSelectedProject(clientId: String, projectId: String?): ClientDto? =
        safeRpcCall("updateLastSelectedProject", returnNull = true) {
            clientService.updateLastSelectedProject(clientId, projectId)
        }
}
