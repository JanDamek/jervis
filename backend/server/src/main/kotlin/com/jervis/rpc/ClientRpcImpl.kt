package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ClientDto
import com.jervis.dto.git.GitAnalysisResultDto
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.service.IClientService
import com.jervis.service.client.ClientService
import com.jervis.service.git.GitAnalysisService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ClientRpcImpl(
    private val clientService: ClientService,
    private val gitAnalysisService: GitAnalysisService,
) : IClientService {
    override suspend fun getAllClients(): List<ClientDto> = clientService.list().map { it.toDto() }

    override suspend fun getClientById(id: String): ClientDto? =
        try {
            clientService.getClientById(ClientId(ObjectId(id))).toDto()
        } catch (e: Exception) {
            null
        }

    override suspend fun createClient(client: ClientDto): ClientDto = clientService.create(client.toDocument()).toDto()

    override suspend fun updateClient(
        id: String,
        client: ClientDto,
    ): ClientDto {
        // Ensure ID from path/param matches DTO or is used
        val clientDoc = client.copy(id = id).toDocument()
        return clientService.update(clientDoc).toDto()
    }

    override suspend fun deleteClient(id: String) {
        clientService.delete(ClientId(ObjectId(id)))
    }

    override suspend fun updateLastSelectedProject(
        id: String,
        projectId: String?,
    ): ClientDto {
        val client = clientService.getClientById(ClientId(ObjectId(id)))
        val updatedClient =
            client.copy(
                lastSelectedProjectId = if (projectId.isNullOrBlank()) null else ProjectId(ObjectId(projectId)),
            )
        return clientService.update(updatedClient).toDto()
    }

    override suspend fun analyzeGitRepositories(clientId: String): GitAnalysisResultDto =
        gitAnalysisService.analyzeClientGitRepositories(ClientId(ObjectId(clientId)))
}
