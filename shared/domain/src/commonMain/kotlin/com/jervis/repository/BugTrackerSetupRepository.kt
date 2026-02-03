package com.jervis.repository

import com.jervis.dto.bugtracker.BugTrackerApiTokenSaveRequestDto
import com.jervis.dto.bugtracker.BugTrackerApiTokenTestRequestDto
import com.jervis.dto.bugtracker.BugTrackerApiTokenTestResponseDto
import com.jervis.dto.bugtracker.BugTrackerBoardRefDto
import com.jervis.dto.bugtracker.BugTrackerProjectRefDto
import com.jervis.dto.bugtracker.BugTrackerProjectSelectionDto
import com.jervis.dto.bugtracker.BugTrackerSetupStatusDto
import com.jervis.service.IBugTrackerSetupService

/**
 * Repository for BugTracker setup operations.
 * Wraps IBugTrackerSetupService for use by UI.
 */
class BugTrackerSetupRepository(
    private val service: IBugTrackerSetupService,
) {
    suspend fun getStatus(clientId: String): BugTrackerSetupStatusDto =
        service.getStatus(clientId)

    suspend fun testApiToken(tenant: String, email: String, apiToken: String): BugTrackerApiTokenTestResponseDto =
        service.testApiToken(BugTrackerApiTokenTestRequestDto(tenant = tenant, email = email, apiToken = apiToken))

    suspend fun saveApiToken(clientId: String, tenant: String, email: String, apiToken: String): BugTrackerSetupStatusDto =
        service.saveApiToken(BugTrackerApiTokenSaveRequestDto(clientId = clientId, tenant = tenant, email = email, apiToken = apiToken))

    suspend fun listProjects(clientId: String): List<BugTrackerProjectRefDto> =
        service.listProjects(clientId)

    suspend fun testConnection(clientId: String): BugTrackerSetupStatusDto =
        service.testConnection(clientId)

    /**
     * Sets the primary BugTracker project for the client.
     */
    suspend fun setPrimaryProject(clientId: String, projectKey: String): BugTrackerSetupStatusDto =
        service.setPrimaryProject(BugTrackerProjectSelectionDto(clientId = clientId, projectKey = projectKey))

    /**
     * Lists BugTracker boards for the client, optionally filtered by project key.
     */
    suspend fun listBoards(clientId: String, projectKey: String? = null): List<BugTrackerBoardRefDto> =
        service.listBoards(clientId = clientId, projectKey = projectKey)
}
