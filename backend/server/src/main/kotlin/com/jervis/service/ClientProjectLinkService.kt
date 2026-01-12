package com.jervis.service

import com.jervis.dto.ClientProjectLinkDto
import org.springframework.stereotype.Service

@Service
class ClientProjectLinkService : IClientProjectLinkService {
    override suspend fun listForClient(clientId: String): List<ClientProjectLinkDto> = emptyList()

    override suspend fun get(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto? = null

    override suspend fun upsert(
        clientId: String,
        projectId: String,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto {
        TODO("Not yet implemented")
    }

    override suspend fun toggleDisabled(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto {
        TODO("Not yet implemented")
    }

    override suspend fun toggleAnonymization(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto {
        TODO("Not yet implemented")
    }

    override suspend fun toggleHistorical(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto {
        TODO("Not yet implemented")
    }

    override suspend fun delete(
        clientId: String,
        projectId: String,
    ) {
        // Not yet implemented
    }
}
