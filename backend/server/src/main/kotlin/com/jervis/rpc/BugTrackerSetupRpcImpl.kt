package com.jervis.rpc

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
import com.jervis.service.IBugTrackerSetupService
import org.springframework.stereotype.Component

@Component
class BugTrackerSetupRpcImpl : IBugTrackerSetupService {
    override suspend fun getStatus(clientId: String): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = clientId, connected = false)
    }

    override suspend fun testApiToken(request: BugTrackerApiTokenTestRequestDto): BugTrackerApiTokenTestResponseDto {
        // TODO: Implement
        return BugTrackerApiTokenTestResponseDto(success = false, message = "Not implemented")
    }

    override suspend fun saveApiToken(request: BugTrackerApiTokenSaveRequestDto): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = request.clientId, connected = false)
    }

    override suspend fun beginAuth(request: BugTrackerBeginAuthRequestDto): BugTrackerBeginAuthResponseDto {
        // TODO: Implement
        return BugTrackerBeginAuthResponseDto(authUrl = "", correlationId = "")
    }

    override suspend fun completeAuth(request: BugTrackerCompleteAuthRequestDto): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = request.clientId, connected = false)
    }

    override suspend fun setPrimaryProject(request: BugTrackerProjectSelectionDto): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = request.clientId, connected = false)
    }

    override suspend fun setMainBoard(request: BugTrackerBoardSelectionDto): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = request.clientId, connected = false)
    }

    override suspend fun setPreferredUser(request: BugTrackerUserSelectionDto): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = request.clientId, connected = false)
    }

    override suspend fun testConnection(clientId: String): BugTrackerSetupStatusDto {
        // TODO: Implement
        return BugTrackerSetupStatusDto(clientId = clientId, connected = false)
    }

    override suspend fun listProjects(clientId: String): List<BugTrackerProjectRefDto> {
        // TODO: Implement
        return emptyList()
    }

    override suspend fun listBoards(clientId: String, projectKey: String?): List<BugTrackerBoardRefDto> {
        // TODO: Implement
        return emptyList()
    }
}
