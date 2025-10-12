package com.jervis.service

import com.jervis.entity.mongo.ClientProjectLinkDocument
import org.bson.types.ObjectId

interface IClientProjectLinkService {
    suspend fun listForClient(clientId: ObjectId): List<ClientProjectLinkDocument>

    suspend fun get(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument?

    suspend fun upsert(
        clientId: ObjectId,
        projectId: ObjectId,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDocument

    suspend fun toggleDisabled(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument

    suspend fun toggleAnonymization(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument

    suspend fun toggleHistorical(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument

    suspend fun delete(
        clientId: ObjectId,
        projectId: ObjectId,
    )
}
