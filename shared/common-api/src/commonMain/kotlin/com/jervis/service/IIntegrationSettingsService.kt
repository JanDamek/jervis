package com.jervis.service

import com.jervis.dto.integration.ClientWikiDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IIntegrationSettingsService {
    suspend fun getClientStatus(clientId: String): IntegrationClientStatusDto

    suspend fun setClientWikiDefaults(request: ClientWikiDefaultsDto): IntegrationClientStatusDto

    suspend fun getProjectStatus(projectId: String): IntegrationProjectStatusDto

    suspend fun setProjectOverrides(request: ProjectIntegrationOverridesDto): IntegrationProjectStatusDto
}
