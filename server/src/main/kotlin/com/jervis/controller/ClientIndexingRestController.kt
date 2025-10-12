package com.jervis.controller

import com.jervis.dto.ClientDescriptionResult
import com.jervis.service.IClientIndexingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/client-indexing")
class ClientIndexingRestController(
    private val clientIndexingService: IClientIndexingService,
) {
    @PostMapping("/update-descriptions/{clientId}")
    suspend fun updateClientDescriptions(
        @PathVariable clientId: String,
    ): ClientDescriptionResult = clientIndexingService.updateClientDescriptions(ObjectId(clientId))
}
