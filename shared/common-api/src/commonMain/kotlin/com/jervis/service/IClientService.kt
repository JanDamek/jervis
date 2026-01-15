package com.jervis.service

import com.jervis.dto.ClientDto
import kotlinx.rpc.annotations.Rpc

/**
 * Client Service API
 */
@Rpc
interface IClientService {

    suspend fun getAllClients(): List<ClientDto>

    suspend fun getClientById(id: String): ClientDto?

    suspend fun createClient(client: ClientDto): ClientDto

    suspend fun updateClient(
        id: String,
        client: ClientDto
    ): ClientDto

    suspend fun deleteClient(id: String)

    suspend fun updateLastSelectedProject(
        id: String,
        projectId: String?
    ): ClientDto
}
