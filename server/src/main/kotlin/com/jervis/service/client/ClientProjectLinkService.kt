package com.jervis.service.client

import com.jervis.entity.mongo.ClientProjectLinkDocument
import com.jervis.repository.mongo.ClientProjectLinkMongoRepository
import com.jervis.service.IClientProjectLinkService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClientProjectLinkService(
    private val linkRepo: ClientProjectLinkMongoRepository,
) : IClientProjectLinkService {
    private val logger = KotlinLogging.logger {}

    override suspend fun listForClient(clientId: ObjectId): List<ClientProjectLinkDocument> = linkRepo.findByClientId(clientId).toList()

    override suspend fun get(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument? = linkRepo.findByClientIdAndProjectId(clientId, projectId)

    override suspend fun upsert(
        clientId: ObjectId,
        projectId: ObjectId,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDocument {
        val existing = linkRepo.findByClientIdAndProjectId(clientId, projectId)
        val now = Instant.now()
        return if (existing == null) {
            val link =
                ClientProjectLinkDocument(
                    clientId = clientId,
                    projectId = projectId,
                    isDisabled = isDisabled ?: false,
                    anonymizationEnabled = anonymizationEnabled ?: true,
                    historical = historical ?: false,
                    createdAt = now,
                    updatedAt = now,
                )
            linkRepo.save(link)
        } else {
            val updated =
                existing.copy(
                    isDisabled = isDisabled ?: existing.isDisabled,
                    anonymizationEnabled = anonymizationEnabled ?: existing.anonymizationEnabled,
                    historical = historical ?: existing.historical,
                    updatedAt = now,
                )
            linkRepo.save(updated)
        }
    }

    override suspend fun toggleDisabled(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument =
        upsert(
            clientId,
            projectId,
            isDisabled = !(get(clientId, projectId)?.isDisabled ?: false),
            anonymizationEnabled = null,
            historical = null,
        )

    override suspend fun toggleAnonymization(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument =
        upsert(
            clientId,
            projectId,
            isDisabled = null,
            anonymizationEnabled = !(get(clientId, projectId)?.anonymizationEnabled ?: true),
            historical = null,
        )

    override suspend fun toggleHistorical(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument =
        upsert(
            clientId,
            projectId,
            isDisabled = null,
            anonymizationEnabled = null,
            historical = !(get(clientId, projectId)?.historical ?: false),
        )

    override suspend fun delete(
        clientId: ObjectId,
        projectId: ObjectId,
    ) {
        linkRepo.deleteByClientIdAndProjectId(clientId, projectId)
        logger.info { "Deleted client-project link $clientId -> $projectId" }
    }
}
