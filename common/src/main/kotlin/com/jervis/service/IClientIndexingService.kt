package com.jervis.service

import com.jervis.dto.ClientDescriptionResultDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/client-indexing")
interface IClientIndexingService {
    @PostExchange("/update-descriptions/{clientId}")
    suspend fun updateClientDescriptions(
        @PathVariable clientId: String,
    ): ClientDescriptionResultDto
}
