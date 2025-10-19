package com.jervis.controller.api

import com.jervis.dto.ClientDescriptionResultDto
import com.jervis.service.IClientIndexingService
import com.jervis.service.indexing.ClientIndexingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController

@RestController
class ClientIndexingRestController(
    private val clientIndexingService: ClientIndexingService,
) : IClientIndexingService {
    override suspend fun updateClientDescriptions(clientId: String): ClientDescriptionResultDto =
        clientIndexingService.updateClientDescriptions(ObjectId(clientId))
}
