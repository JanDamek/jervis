package com.jervis.service

import com.jervis.dto.ClientProjectLinkDto
import org.bson.types.ObjectId

interface IClientProjectLinkService {
    suspend fun listForClient(clientId: ObjectId): List<ClientProjectLinkDto>

    suspend fun get(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto?

    suspend fun upsert(
        clientId: ObjectId,
        projectId: ObjectId,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto

    suspend fun toggleDisabled(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto

    suspend fun toggleAnonymization(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto

    suspend fun toggleHistorical(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto

    suspend fun delete(
        clientId: ObjectId,
        projectId: ObjectId,
    )
}
