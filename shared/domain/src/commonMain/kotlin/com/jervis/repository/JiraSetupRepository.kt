package com.jervis.repository

import com.jervis.dto.jira.JiraApiTokenSaveRequestDto
import com.jervis.dto.jira.JiraApiTokenTestRequestDto
import com.jervis.dto.jira.JiraApiTokenTestResponseDto
import com.jervis.dto.jira.JiraProjectRefDto
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
}
