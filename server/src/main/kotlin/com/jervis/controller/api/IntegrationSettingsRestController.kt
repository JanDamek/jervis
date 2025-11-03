package com.jervis.controller.api

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.JiraConnectionMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.IIntegrationSettingsService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class IntegrationSettingsRestController(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
    private val jiraConnectionRepository: JiraConnectionMongoRepository,
    private val errorPublisher: com.jervis.service.notification.ErrorNotificationsPublisher,
) : IIntegrationSettingsService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getClientStatus(clientId: String): IntegrationClientStatusDto =
        try {
            val client =
                clientRepository.findById(ObjectId(clientId))
                    ?: return IntegrationClientStatusDto(clientId = clientId, jiraConnected = false)
            val jiraConn = jiraConnectionRepository.findByClientId(client.id)
            val jiraConnected = jiraConn != null && jiraConn.expiresAt.isAfter(Instant.now())
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

    override suspend fun setClientConfluenceDefaults(request: ClientConfluenceDefaultsDto): IntegrationClientStatusDto {
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

    override suspend fun getProjectStatus(projectId: String): IntegrationProjectStatusDto {
        val project =
            requireNotNull(projectRepository.findById(ObjectId(projectId))) { "Project not found: $projectId" }
        val client =
            requireNotNull(clientRepository.findById(project.clientId)) { "Client not found: ${project.clientId}" }
        val jiraConn = jiraConnectionRepository.findByClientId(client.id)
        val override = project.overrides

        val effectiveJiraProject = override?.jiraProjectKey ?: jiraConn?.primaryProject
        val effectiveSpace = override?.confluenceSpaceKey ?: client.confluenceSpaceKey
        val effectiveRoot = override?.confluenceRootPageId ?: client.confluenceRootPageId

        return IntegrationProjectStatusDto(
            projectId = project.id.toHexString(),
            clientId = client.id.toHexString(),
            effectiveJiraProjectKey = effectiveJiraProject,
            overrideJiraProjectKey = override?.jiraProjectKey,
            effectiveConfluenceSpaceKey = effectiveSpace,
            overrideConfluenceSpaceKey = override?.confluenceSpaceKey,
            effectiveConfluenceRootPageId = effectiveRoot,
            overrideConfluenceRootPageId = override?.confluenceRootPageId,
        )
    }

    override suspend fun setProjectOverrides(request: ProjectIntegrationOverridesDto): IntegrationProjectStatusDto {
        val project =
            requireNotNull(projectRepository.findById(ObjectId(request.projectId))) { "Project not found: ${request.projectId}" }
        val currentOverrides = project.overrides
        val updatedOverrides =
            com.jervis.domain.project.ProjectOverrides(
                gitRemoteUrl = currentOverrides?.gitRemoteUrl,
                gitAuthType = currentOverrides?.gitAuthType,
                gitConfig = currentOverrides?.gitConfig,
                jiraProjectKey = request.jiraProjectKey ?: currentOverrides?.jiraProjectKey,
                confluenceSpaceKey = request.confluenceSpaceKey ?: currentOverrides?.confluenceSpaceKey,
                confluenceRootPageId = request.confluenceRootPageId ?: currentOverrides?.confluenceRootPageId,
            )
        val updated: ProjectDocument = project.copy(overrides = updatedOverrides, updatedAt = Instant.now())
        projectRepository.save(updated)
        logger.info { "INTEGRATION_SETTINGS: Updated project overrides for project ${updated.id}" }
        return getProjectStatus(updated.id.toHexString())
    }
}
