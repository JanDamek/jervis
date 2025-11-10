package com.jervis.service

import com.jervis.dto.integration.ClientConfluenceDefaultsDto
import com.jervis.dto.integration.IntegrationClientStatusDto
import com.jervis.dto.integration.IntegrationProjectStatusDto
import com.jervis.dto.integration.ProjectIntegrationOverridesDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Query

interface IIntegrationSettingsService {
    @GET("api/integration/settings/client-status")
    suspend fun getClientStatus(
        @Query clientId: String,
    ): IntegrationClientStatusDto

    @PUT("api/integration/settings/client/confluence")
    suspend fun setClientConfluenceDefaults(
        @Body request: ClientConfluenceDefaultsDto,
    ): IntegrationClientStatusDto

    @GET("api/integration/settings/project-status")
    suspend fun getProjectStatus(
        @Query projectId: String,
    ): IntegrationProjectStatusDto

    @PUT("api/integration/settings/project-overrides")
    suspend fun setProjectOverrides(
        @Body request: ProjectIntegrationOverridesDto,
    ): IntegrationProjectStatusDto
}
