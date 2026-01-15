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
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IAtlassianSetupService {
    suspend fun getStatus(clientId: String): AtlassianSetupStatusDto

    suspend fun testApiToken(request: AtlassianApiTokenTestRequestDto): AtlassianApiTokenTestResponseDto

    suspend fun saveApiToken(request: AtlassianApiTokenSaveRequestDto): AtlassianSetupStatusDto

    suspend fun beginAuth(request: AtlassianBeginAuthRequestDto): AtlassianBeginAuthResponseDto

    suspend fun completeAuth(request: AtlassianCompleteAuthRequestDto): AtlassianSetupStatusDto

    suspend fun setPrimaryProject(request: AtlassianProjectSelectionDto): AtlassianSetupStatusDto

    suspend fun setMainBoard(request: AtlassianBoardSelectionDto): AtlassianSetupStatusDto

    suspend fun setPreferredUser(request: AtlassianUserSelectionDto): AtlassianSetupStatusDto

    suspend fun testConnection(clientId: String): AtlassianSetupStatusDto

    suspend fun listProjects(clientId: String): List<AtlassianProjectRefDto>

    suspend fun listBoards(
        clientId: String,
        projectKey: String? = null,
    ): List<AtlassianBoardRefDto>
}
