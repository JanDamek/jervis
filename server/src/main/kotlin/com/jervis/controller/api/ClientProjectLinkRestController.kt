package com.jervis.controller.api

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.client.ClientProjectLinkService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController

@RestController
class ClientProjectLinkRestController(
    private val linkService: ClientProjectLinkService,
) : IClientProjectLinkService {
    override suspend fun listForClient(clientId: String): List<ClientProjectLinkDto> = linkService.listForClient(ObjectId(clientId))

    override suspend fun get(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto? = linkService.get(ObjectId(clientId), ObjectId(projectId))

    override suspend fun upsert(
        clientId: String,
        projectId: String,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto =
        linkService.upsert(
            ObjectId(clientId),
            ObjectId(projectId),
            isDisabled,
            anonymizationEnabled,
            historical,
        )

    override suspend fun toggleDisabled(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = linkService.toggleDisabled(ObjectId(clientId), ObjectId(projectId))

    override suspend fun toggleAnonymization(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = linkService.toggleAnonymization(ObjectId(clientId), ObjectId(projectId))

    override suspend fun toggleHistorical(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = linkService.toggleHistorical(ObjectId(clientId), ObjectId(projectId))

    override suspend fun delete(
        clientId: String,
        projectId: String,
    ) {
        linkService.delete(ObjectId(clientId), ObjectId(projectId))
    }
}
