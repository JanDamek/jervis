package com.jervis.service

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/integration/settings")
interface IIntegrationSettingsService {
    @GetExchange("/client-status")
    suspend fun getClientStatus(
        @RequestParam clientId: String,
    ): IntegrationClientStatusDto

    @PutExchange("/client/confluence")
    suspend fun setClientConfluenceDefaults(
        @RequestBody request: ClientConfluenceDefaultsDto,
    ): IntegrationClientStatusDto

    @GetExchange("/project-status")
    suspend fun getProjectStatus(
        @RequestParam projectId: String,
    ): IntegrationProjectStatusDto

    @PutExchange("/project-overrides")
    suspend fun setProjectOverrides(
        @RequestBody request: ProjectIntegrationOverridesDto,
    ): IntegrationProjectStatusDto
}
