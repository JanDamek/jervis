package com.jervis.repository

import com.jervis.dto.jira.JiraApiTokenSaveRequestDto
import com.jervis.dto.jira.JiraApiTokenTestRequestDto
import com.jervis.dto.jira.JiraApiTokenTestResponseDto
import com.jervis.dto.jira.JiraBoardRefDto
import com.jervis.dto.jira.JiraProjectRefDto
import com.jervis.dto.jira.JiraProjectSelectionDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.service.IJiraSetupService

/**
 * Repository for Jira setup operations.
 * Wraps IJiraSetupService for use by UI.
 */
class JiraSetupRepository(
    private val service: IJiraSetupService,
) {
    suspend fun getStatus(clientId: String): JiraSetupStatusDto =
        service.getStatus(clientId)

    suspend fun testApiToken(tenant: String, email: String, apiToken: String): JiraApiTokenTestResponseDto =
        service.testApiToken(JiraApiTokenTestRequestDto(tenant = tenant, email = email, apiToken = apiToken))

    suspend fun saveApiToken(clientId: String, tenant: String, email: String, apiToken: String): JiraSetupStatusDto =
        service.saveApiToken(JiraApiTokenSaveRequestDto(clientId = clientId, tenant = tenant, email = email, apiToken = apiToken))

    suspend fun listProjects(clientId: String): List<JiraProjectRefDto> =
        service.listProjects(clientId)

    suspend fun testConnection(clientId: String): JiraSetupStatusDto =
        service.testConnection(clientId)

    /**
     * Sets the primary Jira project for the client.
     */
    suspend fun setPrimaryProject(clientId: String, projectKey: String): JiraSetupStatusDto =
        service.setPrimaryProject(JiraProjectSelectionDto(clientId = clientId, projectKey = projectKey))

    /**
     * Lists Jira boards for the client, optionally filtered by project key.
     */
    suspend fun listBoards(clientId: String, projectKey: String? = null): List<JiraBoardRefDto> =
        service.listBoards(clientId = clientId, projectKey = projectKey)
}
