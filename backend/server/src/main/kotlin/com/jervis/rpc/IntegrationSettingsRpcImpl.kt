package com.jervis.rpc

import com.jervis.dto.integration.ClientWikiDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import com.jervis.service.IIntegrationSettingsService
import org.springframework.stereotype.Component

@Component
class IntegrationSettingsRpcImpl : IIntegrationSettingsService {
    override suspend fun getClientStatus(clientId: String): IntegrationClientStatusDto {
        // TODO: Implement
        return IntegrationClientStatusDto(
            clientId = clientId,
            bugtrackerConnected = false
        )
    }

    override suspend fun setClientWikiDefaults(request: ClientWikiDefaultsDto): IntegrationClientStatusDto {
        // TODO: Implement
        return IntegrationClientStatusDto(
            clientId = request.clientId,
            bugtrackerConnected = false
        )
    }

    override suspend fun getProjectStatus(projectId: String): IntegrationProjectStatusDto {
        // TODO: Implement
        return IntegrationProjectStatusDto(
            projectId = projectId,
            clientId = "unknown" // TODO: Fetch from project service
        )
    }

    override suspend fun setProjectOverrides(request: ProjectIntegrationOverridesDto): IntegrationProjectStatusDto {
        // TODO: Implement
        return IntegrationProjectStatusDto(
            projectId = request.projectId,
            clientId = "unknown" // TODO: Fetch from project service
        )
    }
}
