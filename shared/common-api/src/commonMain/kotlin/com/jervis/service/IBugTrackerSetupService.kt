package com.jervis.service

import com.jervis.dto.bugtracker.BugTrackerApiTokenSaveRequestDto
import com.jervis.dto.bugtracker.BugTrackerApiTokenTestRequestDto
import com.jervis.dto.bugtracker.BugTrackerApiTokenTestResponseDto
import com.jervis.dto.bugtracker.BugTrackerBeginAuthRequestDto
import com.jervis.dto.bugtracker.BugTrackerBeginAuthResponseDto
import com.jervis.dto.bugtracker.BugTrackerBoardRefDto
import com.jervis.dto.bugtracker.BugTrackerBoardSelectionDto
import com.jervis.dto.bugtracker.BugTrackerCompleteAuthRequestDto
import com.jervis.dto.bugtracker.BugTrackerProjectRefDto
import com.jervis.dto.bugtracker.BugTrackerProjectSelectionDto
import com.jervis.dto.bugtracker.BugTrackerSetupStatusDto
import com.jervis.dto.bugtracker.BugTrackerUserSelectionDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IBugTrackerSetupService {
    suspend fun getStatus(clientId: String): BugTrackerSetupStatusDto

    suspend fun testApiToken(request: BugTrackerApiTokenTestRequestDto): BugTrackerApiTokenTestResponseDto

    suspend fun saveApiToken(request: BugTrackerApiTokenSaveRequestDto): BugTrackerSetupStatusDto

    suspend fun beginAuth(request: BugTrackerBeginAuthRequestDto): BugTrackerBeginAuthResponseDto

    suspend fun completeAuth(request: BugTrackerCompleteAuthRequestDto): BugTrackerSetupStatusDto

    suspend fun setPrimaryProject(request: BugTrackerProjectSelectionDto): BugTrackerSetupStatusDto

    suspend fun setMainBoard(request: BugTrackerBoardSelectionDto): BugTrackerSetupStatusDto

    suspend fun setPreferredUser(request: BugTrackerUserSelectionDto): BugTrackerSetupStatusDto

    suspend fun testConnection(clientId: String): BugTrackerSetupStatusDto

    suspend fun listProjects(clientId: String): List<BugTrackerProjectRefDto>

    suspend fun listBoards(
        clientId: String,
        projectKey: String? = null,
    ): List<BugTrackerBoardRefDto>
}
