package com.jervis.git

import com.jervis.dto.client.ClientDto
import com.jervis.dto.git.CloneResultDto
import com.jervis.dto.git.GitBranchListDto
import com.jervis.dto.git.GitCredentialsDto
import com.jervis.dto.git.GitSetupRequestDto
import com.jervis.dto.git.GitTestConnectionResponseDto
import com.jervis.dto.project.ProjectDto
import com.jervis.dto.project.ProjectGitOverrideRequestDto
import com.jervis.service.git.IGitConfigurationService
import org.springframework.stereotype.Service

@Service
class GitConfigurationService : IGitConfigurationService {
    override suspend fun setupGitConfiguration(
        clientId: String,
        request: GitSetupRequestDto,
    ): ClientDto {
        TODO("Not yet implemented")
    }

    override suspend fun testConnection(
        clientId: String,
        request: GitSetupRequestDto,
    ): GitTestConnectionResponseDto = GitTestConnectionResponseDto(success = false, message = "Not yet implemented")

    override suspend fun cloneRepository(clientId: String): CloneResultDto {
        TODO("Not yet implemented")
    }

    override suspend fun inheritGitConfig(
        projectId: String,
        clientId: String,
    ): Map<String, Any> = mapOf("success" to false, "message" to "Not yet implemented")

    override suspend fun getGitCredentials(clientId: String): GitCredentialsDto? = null

    override suspend fun listRemoteBranches(
        clientId: String,
        repoUrl: String?,
    ): GitBranchListDto = GitBranchListDto(defaultBranch = null, branches = emptyList())

    override suspend fun setDefaultBranch(
        clientId: String,
        branch: String,
    ): ClientDto {
        TODO("Not yet implemented")
    }

    override suspend fun setupGitOverrideForProject(
        projectId: String,
        request: ProjectGitOverrideRequestDto,
    ): ProjectDto {
        TODO("Not yet implemented")
    }

    override suspend fun clearGitOverrideForProject(projectId: String): ProjectDto {
        TODO("Not yet implemented")
    }
}
