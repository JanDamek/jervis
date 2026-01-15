package com.jervis.rpc

import com.jervis.dto.ClientDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IClientService
import com.jervis.service.client.ClientService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ClientRpcImpl(
    private val clientService: ClientService
) : IClientService {

    override suspend fun getAllClients(): List<ClientDto> =
        clientService.list().map { it.toDto() }

    override suspend fun getClientById(id: String): ClientDto? =
        try {
            clientService.getClientById(ClientId(ObjectId(id))).toDto()
        } catch (e: Exception) {
            null
        }

    override suspend fun createClient(client: ClientDto): ClientDto =
        clientService.create(client.toDocument()).toDto()

    override suspend fun updateClient(id: String, client: ClientDto): ClientDto {
        // Ensure ID from path/param matches DTO or is used
        val clientDoc = client.copy(id = id).toDocument()
        return clientService.update(clientDoc).toDto()
    }

    override suspend fun deleteClient(id: String) {
        clientService.delete(ClientId(ObjectId(id)))
    }

    override suspend fun updateLastSelectedProject(id: String, projectId: String?): ClientDto {
        val client = clientService.getClientById(ClientId(ObjectId(id)))
        val updatedClient = client.copy(
            lastSelectedProjectId = projectId?.let { ProjectId(ObjectId(it)) }
        )
        return clientService.update(updatedClient).toDto()
    }
}
