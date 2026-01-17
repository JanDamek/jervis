package com.jervis.rpc

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.service.IClientProjectLinkService

import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ClientProjectLinkRpcImpl(
    private val clientProjectLinkService: com.jervis.service.ClientProjectLinkService,
) : IClientProjectLinkService {
    override suspend fun listForClient(clientId: String): List<ClientProjectLinkDto> =
        clientProjectLinkService.listForClient(clientId)

    override suspend fun get(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto? = clientProjectLinkService.get(clientId, projectId)

    override suspend fun upsert(
        clientId: String,
        projectId: String,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto =
        clientProjectLinkService.upsert(clientId, projectId, isDisabled, anonymizationEnabled, historical)

    override suspend fun toggleDisabled(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = clientProjectLinkService.toggleDisabled(clientId, projectId)

    override suspend fun toggleAnonymization(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = clientProjectLinkService.toggleAnonymization(clientId, projectId)

    override suspend fun toggleHistorical(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = clientProjectLinkService.toggleHistorical(clientId, projectId)

    override suspend fun delete(
        clientId: String,
        projectId: String,
    ) = clientProjectLinkService.delete(clientId, projectId)
}
