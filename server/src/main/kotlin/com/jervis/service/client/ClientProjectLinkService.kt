package com.jervis.service.client

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.entity.mongo.ClientProjectLinkDocument
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ClientProjectLinkMongoRepository
import com.jervis.service.IClientProjectLinkService
import kotlinx.coroutines.flow.map
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

    override suspend fun listForClient(clientId: ObjectId): List<ClientProjectLinkDto> =
        linkRepo.findByClientId(clientId).map { it.toDto() }.toList()

    override suspend fun get(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto? = linkRepo.findByClientIdAndProjectId(clientId, projectId)?.toDto()

    override suspend fun upsert(
        clientId: ObjectId,
        projectId: ObjectId,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto {
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
            linkRepo.save(link).toDto()
        } else {
            val updated =
                existing.copy(
                    isDisabled = isDisabled ?: existing.isDisabled,
                    anonymizationEnabled = anonymizationEnabled ?: existing.anonymizationEnabled,
                    historical = historical ?: existing.historical,
                    updatedAt = now,
                )
            linkRepo.save(updated).toDto()
        }
    }

    override suspend fun toggleDisabled(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto =
        upsert(
            clientId,
            projectId,
            isDisabled = !(linkRepo.findByClientIdAndProjectId(clientId, projectId)?.isDisabled ?: false),
            anonymizationEnabled = null,
            historical = null,
        )

    override suspend fun toggleAnonymization(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto =
        upsert(
            clientId,
            projectId,
            isDisabled = null,
            anonymizationEnabled =
                !(
                    linkRepo.findByClientIdAndProjectId(clientId, projectId)?.anonymizationEnabled
                        ?: true
                ),
            historical = null,
        )

    override suspend fun toggleHistorical(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDto =
        upsert(
            clientId,
            projectId,
            isDisabled = null,
            anonymizationEnabled = null,
            historical = !(linkRepo.findByClientIdAndProjectId(clientId, projectId)?.historical ?: false),
        )

    override suspend fun delete(
        clientId: ObjectId,
        projectId: ObjectId,
    ) {
        linkRepo.deleteByClientIdAndProjectId(clientId, projectId)
        logger.info { "Deleted client-project link $clientId -> $projectId" }
    }
}
