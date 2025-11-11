package com.jervis.repository

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import com.jervis.service.IIntegrationSettingsService

/**
 * Repository for integration settings (Jira/Confluence overrides and statuses).
 * Wraps IIntegrationSettingsService for use by UI.
 */
class IntegrationSettingsRepository(
    private val service: IIntegrationSettingsService,
) {
    suspend fun getClientStatus(clientId: String): IntegrationClientStatusDto =
        service.getClientStatus(clientId)

    suspend fun setClientConfluenceDefaults(request: ClientConfluenceDefaultsDto): IntegrationClientStatusDto =
        service.setClientConfluenceDefaults(request)

    suspend fun getProjectStatus(projectId: String): IntegrationProjectStatusDto =
        service.getProjectStatus(projectId)

    suspend fun setProjectOverrides(request: ProjectIntegrationOverridesDto): IntegrationProjectStatusDto =
        service.setProjectOverrides(request)
}
