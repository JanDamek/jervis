package com.jervis.controller.api

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.client.ClientProjectLinkService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/client-project-links")
class ClientProjectLinkRestController(
    private val linkService: ClientProjectLinkService,
) : IClientProjectLinkService {
    @GetMapping("/client/{clientId}")
    override suspend fun listForClient(@PathVariable clientId: String): List<ClientProjectLinkDto> = linkService.listForClient(ObjectId(clientId))

    @GetMapping("/client/{clientId}/project/{projectId}")
    override suspend fun get(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto? = linkService.get(ObjectId(clientId), ObjectId(projectId))

    @PostMapping("/client/{clientId}/project/{projectId}")
    override suspend fun upsert(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
        @RequestParam(required = false) isDisabled: Boolean?,
        @RequestParam(required = false) anonymizationEnabled: Boolean?,
        @RequestParam(required = false) historical: Boolean?,
    ): ClientProjectLinkDto =
        linkService.upsert(
            ObjectId(clientId),
            ObjectId(projectId),
            isDisabled,
            anonymizationEnabled,
            historical,
        )

    @PutMapping("/client/{clientId}/project/{projectId}/toggle-disabled")
    override suspend fun toggleDisabled(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto = linkService.toggleDisabled(ObjectId(clientId), ObjectId(projectId))

    @PutMapping("/client/{clientId}/project/{projectId}/toggle-anonymization")
    override suspend fun toggleAnonymization(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto = linkService.toggleAnonymization(ObjectId(clientId), ObjectId(projectId))

    @PutMapping("/client/{clientId}/project/{projectId}/toggle-historical")
    override suspend fun toggleHistorical(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto = linkService.toggleHistorical(ObjectId(clientId), ObjectId(projectId))

    @DeleteMapping("/client/{clientId}/project/{projectId}")
    override suspend fun delete(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ) {
        linkService.delete(ObjectId(clientId), ObjectId(projectId))
    }
}
