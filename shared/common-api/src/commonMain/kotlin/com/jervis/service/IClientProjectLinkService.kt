package com.jervis.service

import com.jervis.dto.ClientProjectLinkDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IClientProjectLinkService {
    suspend fun listForClient(clientId: String): List<ClientProjectLinkDto>

    suspend fun get(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto?

    suspend fun upsert(
        clientId: String,
        projectId: String,
        isDisabled: Boolean? = null,
        anonymizationEnabled: Boolean? = null,
        historical: Boolean? = null,
    ): ClientProjectLinkDto

    suspend fun toggleDisabled(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto

    suspend fun toggleAnonymization(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto

    suspend fun toggleHistorical(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto

    suspend fun delete(
        clientId: String,
        projectId: String,
    )
}
