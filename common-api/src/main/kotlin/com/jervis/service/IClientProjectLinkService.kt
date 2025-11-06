package com.jervis.service

import com.jervis.dto.ClientProjectLinkDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/client-project-links")
interface IClientProjectLinkService {
    @GetExchange("/client/{clientId}")
    suspend fun listForClient(
        @PathVariable clientId: String,
    ): List<ClientProjectLinkDto>

    @GetExchange("/client/{clientId}/project/{projectId}")
    suspend fun get(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto?

    @PostExchange("/client/{clientId}/project/{projectId}")
    suspend fun upsert(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
        @RequestParam(required = false) isDisabled: Boolean?,
        @RequestParam(required = false) anonymizationEnabled: Boolean?,
        @RequestParam(required = false) historical: Boolean?,
    ): ClientProjectLinkDto

    @PutExchange("/client/{clientId}/project/{projectId}/toggle-disabled")
    suspend fun toggleDisabled(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto

    @PutExchange("/client/{clientId}/project/{projectId}/toggle-anonymization")
    suspend fun toggleAnonymization(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto

    @PutExchange("/client/{clientId}/project/{projectId}/toggle-historical")
    suspend fun toggleHistorical(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    ): ClientProjectLinkDto

    @DeleteExchange("/client/{clientId}/project/{projectId}")
    suspend fun delete(
        @PathVariable clientId: String,
        @PathVariable projectId: String,
    )
}
