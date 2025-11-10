package com.jervis.service

import com.jervis.dto.jira.JiraApiTokenSaveRequestDto
import com.jervis.dto.jira.JiraApiTokenTestRequestDto
import com.jervis.dto.jira.JiraApiTokenTestResponseDto
import com.jervis.dto.jira.JiraBeginAuthRequestDto
import com.jervis.dto.jira.JiraBeginAuthResponseDto
import com.jervis.dto.jira.JiraBoardRefDto
import com.jervis.dto.jira.JiraBoardSelectionDto
import com.jervis.dto.jira.JiraCompleteAuthRequestDto
import com.jervis.dto.jira.JiraProjectRefDto
import com.jervis.dto.jira.JiraProjectSelectionDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.dto.jira.JiraUserSelectionDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Query

interface IJiraSetupService {
    @GET("api/jira/setup/status")
    suspend fun getStatus(
        @Query clientId: String,
    ): JiraSetupStatusDto

    @POST("api/jira/setup/test-api-token")
    suspend fun testApiToken(
        @Body request: JiraApiTokenTestRequestDto,
    ): JiraApiTokenTestResponseDto

    @POST("api/jira/setup/save-api-token")
    suspend fun saveApiToken(
        @Body request: JiraApiTokenSaveRequestDto,
    ): JiraSetupStatusDto

    @POST("api/jira/setup/begin-auth")
    suspend fun beginAuth(
        @Body request: JiraBeginAuthRequestDto,
    ): JiraBeginAuthResponseDto

    @POST("api/jira/setup/complete-auth")
    suspend fun completeAuth(
        @Body request: JiraCompleteAuthRequestDto,
    ): JiraSetupStatusDto

    @PUT("api/jira/setup/primary-project")
    suspend fun setPrimaryProject(
        @Body request: JiraProjectSelectionDto,
    ): JiraSetupStatusDto

    @PUT("api/jira/setup/main-board")
    suspend fun setMainBoard(
        @Body request: JiraBoardSelectionDto,
    ): JiraSetupStatusDto

    @PUT("api/jira/setup/preferred-user")
    suspend fun setPreferredUser(
        @Body request: JiraUserSelectionDto,
    ): JiraSetupStatusDto

    // Lists for UI selection
    @GET("api/jira/setup/projects")
    suspend fun listProjects(
        @Query clientId: String,
    ): List<JiraProjectRefDto>

    @GET("api/jira/setup/boards")
    suspend fun listBoards(
        @Query clientId: String,
        @Query projectKey: String? = null,
    ): List<JiraBoardRefDto>
}
