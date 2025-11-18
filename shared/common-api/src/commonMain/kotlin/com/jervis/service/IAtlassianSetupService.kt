package com.jervis.service

import com.jervis.dto.atlassian.AtlassianApiTokenSaveRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestRequestDto
import com.jervis.dto.atlassian.AtlassianApiTokenTestResponseDto
import com.jervis.dto.atlassian.AtlassianBeginAuthRequestDto
import com.jervis.dto.atlassian.AtlassianBeginAuthResponseDto
import com.jervis.dto.atlassian.AtlassianBoardRefDto
import com.jervis.dto.atlassian.AtlassianBoardSelectionDto
import com.jervis.dto.atlassian.AtlassianCompleteAuthRequestDto
import com.jervis.dto.atlassian.AtlassianProjectRefDto
import com.jervis.dto.atlassian.AtlassianProjectSelectionDto
import com.jervis.dto.atlassian.AtlassianSetupStatusDto
import com.jervis.dto.atlassian.AtlassianUserSelectionDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Query

interface IAtlassianSetupService {
    @GET("api/atlassian/setup/status")
    suspend fun getStatus(
        @Query clientId: String,
    ): AtlassianSetupStatusDto

    @POST("api/atlassian/setup/test-api-token")
    suspend fun testApiToken(
        @Body request: AtlassianApiTokenTestRequestDto,
    ): AtlassianApiTokenTestResponseDto

    @POST("api/atlassian/setup/save-api-token")
    suspend fun saveApiToken(
        @Body request: AtlassianApiTokenSaveRequestDto,
    ): AtlassianSetupStatusDto

    @POST("api/atlassian/setup/begin-auth")
    suspend fun beginAuth(
        @Body request: AtlassianBeginAuthRequestDto,
    ): AtlassianBeginAuthResponseDto

    @POST("api/atlassian/setup/complete-auth")
    suspend fun completeAuth(
        @Body request: AtlassianCompleteAuthRequestDto,
    ): AtlassianSetupStatusDto

    @PUT("api/atlassian/setup/primary-project")
    suspend fun setPrimaryProject(
        @Body request: AtlassianProjectSelectionDto,
    ): AtlassianSetupStatusDto

    @PUT("api/atlassian/setup/main-board")
    suspend fun setMainBoard(
        @Body request: AtlassianBoardSelectionDto,
    ): AtlassianSetupStatusDto

    @PUT("api/atlassian/setup/preferred-user")
    suspend fun setPreferredUser(
        @Body request: AtlassianUserSelectionDto,
    ): AtlassianSetupStatusDto

    /**
     * UI-only action to verify Atlassian token for the client and, on success, enable Jira usage (authStatus → VALID).
     */
    @POST("api/atlassian/setup/test-connection")
    suspend fun testConnection(
        @Query clientId: String,
    ): AtlassianSetupStatusDto

    // Lists for UI selection
    @GET("api/atlassian/setup/projects")
    suspend fun listProjects(
        @Query clientId: String,
    ): List<AtlassianProjectRefDto>

    @GET("api/atlassian/setup/boards")
    suspend fun listBoards(
        @Query clientId: String,
        @Query projectKey: String? = null,
    ): List<AtlassianBoardRefDto>
}
