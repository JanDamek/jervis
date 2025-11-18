package com.jervis.repository

import com.jervis.dto.atlassian.AtlassianApiTokenSaveRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestResponseDto
import com.jervis.dto.atlassian.AtlassianBoardRefDto
import com.jervis.dto.atlassian.AtlassianProjectRefDto
import com.jervis.dto.atlassian.AtlassianProjectSelectionDto
import com.jervis.dto.atlassian.AtlassianSetupStatusDto
import com.jervis.service.IAtlassianSetupService

/**
 * Repository for Jira setup operations.
 * Wraps IAtlassianSetupService for use by UI.
 */
class AtlassianSetupRepository(
    private val service: IAtlassianSetupService,
) {
    suspend fun getStatus(clientId: String): AtlassianSetupStatusDto =
        service.getStatus(clientId)

    suspend fun testApiToken(tenant: String, email: String, apiToken: String): AtlassianApiTokenTestResponseDto =
        service.testApiToken(AtlassianApiTokenTestRequestDto(tenant = tenant, email = email, apiToken = apiToken))

    suspend fun saveApiToken(clientId: String, tenant: String, email: String, apiToken: String): AtlassianSetupStatusDto =
        service.saveApiToken(AtlassianApiTokenSaveRequestDto(clientId = clientId, tenant = tenant, email = email, apiToken = apiToken))

    suspend fun listProjects(clientId: String): List<AtlassianProjectRefDto> =
        service.listProjects(clientId)

    suspend fun testConnection(clientId: String): AtlassianSetupStatusDto =
        service.testConnection(clientId)

    /**
     * Sets the primary Jira project for the client.
     */
    suspend fun setPrimaryProject(clientId: String, projectKey: String): AtlassianSetupStatusDto =
        service.setPrimaryProject(AtlassianProjectSelectionDto(clientId = clientId, projectKey = projectKey))

    /**
     * Lists Jira boards for the client, optionally filtered by project key.
     */
    suspend fun listBoards(clientId: String, projectKey: String? = null): List<AtlassianBoardRefDto> =
        service.listBoards(clientId = clientId, projectKey = projectKey)
}
