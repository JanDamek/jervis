package com.jervis.controller.api

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.AtlassianConnectionMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.IIntegrationSettingsService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/integration/settings")
class IntegrationSettingsRestController(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
    private val jiraConnectionRepository: AtlassianConnectionMongoRepository,
    private val errorPublisher: com.jervis.service.notification.ErrorNotificationsPublisher,
    private val configCache: com.jervis.service.cache.ClientProjectConfigCache,
) : IIntegrationSettingsService {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/client-status")
    override suspend fun getClientStatus(@RequestParam clientId: String): IntegrationClientStatusDto =
        try {
            val client =
                clientRepository.findById(ObjectId(clientId))
                    ?: return IntegrationClientStatusDto(clientId = clientId, jiraConnected = false)
            val jiraConn = jiraConnectionRepository.findByClientId(client.id)
            val jiraConnected = jiraConn != null && jiraConn.authStatus == "VALID"
            IntegrationClientStatusDto(
                clientId = client.id.toHexString(),
                jiraConnected = jiraConnected,
                jiraTenant = jiraConn?.tenant,
                jiraPrimaryProject = jiraConn?.primaryProject,
                confluenceSpaceKey = client.confluenceSpaceKey,
                confluenceRootPageId = client.confluenceRootPageId,
            )
        } catch (e: Exception) {
            errorPublisher.publishError(
                message = "Failed to fetch client integration status: ${e.message}",
                stackTrace = e.stackTraceToString(),
            )
            IntegrationClientStatusDto(
                clientId = clientId,
                jiraConnected = false,
            )
        }

    @PutMapping("/client/confluence")
    override suspend fun setClientConfluenceDefaults(@RequestBody request: ClientConfluenceDefaultsDto): IntegrationClientStatusDto {
        val client =
            requireNotNull(clientRepository.findById(ObjectId(request.clientId))) { "Client not found: ${request.clientId}" }
        val updated: ClientDocument =
            client.copy(
                confluenceSpaceKey = request.confluenceSpaceKey,
                confluenceRootPageId = request.confluenceRootPageId,
                updatedAt = Instant.now(),
            )
        clientRepository.save(updated)
        logger.info { "INTEGRATION_SETTINGS: Updated Confluence defaults for client ${updated.id}" }
        return getClientStatus(updated.id.toHexString())
    }

    @GetMapping("/project-status")
    override suspend fun getProjectStatus(@RequestParam projectId: String): IntegrationProjectStatusDto {
        val project =
            requireNotNull(projectRepository.findById(ObjectId(projectId))) { "Project not found: $projectId" }
        val client =
            requireNotNull(clientRepository.findById(project.clientId)) { "Client not found: ${project.clientId}" }
        val jiraConn = jiraConnectionRepository.findByClientId(client.id)
        val override = project.overrides

        val effectiveJiraProject = override?.jiraProjectKey ?: jiraConn?.primaryProject
        val effectiveSpace = override?.confluenceSpaceKey ?: client.confluenceSpaceKey
        val effectiveRoot = override?.confluenceRootPageId ?: client.confluenceRootPageId
        val effectiveBoardId = override?.jiraBoardId ?: jiraConn?.mainBoard

        return IntegrationProjectStatusDto(
            projectId = project.id.toHexString(),
            clientId = client.id.toHexString(),
            effectiveJiraProjectKey = effectiveJiraProject,
            overrideJiraProjectKey = override?.jiraProjectKey,
            effectiveJiraBoardId = effectiveBoardId,
            overrideJiraBoardId = override?.jiraBoardId,
            effectiveConfluenceSpaceKey = effectiveSpace,
            overrideConfluenceSpaceKey = override?.confluenceSpaceKey,
            effectiveConfluenceRootPageId = effectiveRoot,
            overrideConfluenceRootPageId = override?.confluenceRootPageId,
        )
    }

    @PutMapping("/project-overrides")
    override suspend fun setProjectOverrides(@RequestBody request: ProjectIntegrationOverridesDto): IntegrationProjectStatusDto {
        val project =
            requireNotNull(projectRepository.findById(ObjectId(request.projectId))) { "Project not found: ${request.projectId}" }
        val currentOverrides = project.overrides

        fun String?.applyClear(current: String?): String? =
            when (this) {
                null -> current // unchanged
                "" -> null // explicit clear
                else -> this
            }

        fun String?.applyClearToLong(current: Long?): Long? =
            when (this) {
                null -> current // unchanged
                "" -> null // explicit clear
                else -> this.trim().toLongOrNull() ?: error("Invalid jiraBoardId: '$this'")
            }

        val updatedOverrides =
            com.jervis.domain.project.ProjectOverrides(
                gitRemoteUrl = currentOverrides?.gitRemoteUrl,
                gitAuthType = currentOverrides?.gitAuthType,
                gitConfig = currentOverrides?.gitConfig,
                jiraProjectKey = request.jiraProjectKey.applyClear(currentOverrides?.jiraProjectKey),
                jiraBoardId = request.jiraBoardId.applyClearToLong(currentOverrides?.jiraBoardId),
                confluenceSpaceKey = request.confluenceSpaceKey.applyClear(currentOverrides?.confluenceSpaceKey),
                confluenceRootPageId = request.confluenceRootPageId.applyClear(currentOverrides?.confluenceRootPageId),
            )
        val updated: ProjectDocument = project.copy(overrides = updatedOverrides, updatedAt = Instant.now())
        projectRepository.save(updated)

        // Invalidate cache for this client so changes propagate immediately
        configCache.invalidateClient(project.clientId)

        logger.info { "INTEGRATION_SETTINGS: Updated project overrides for project ${updated.id}, cache invalidated" }
        return getProjectStatus(updated.id.toHexString())
    }
}
