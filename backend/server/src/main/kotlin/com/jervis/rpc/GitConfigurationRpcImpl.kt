package com.jervis.rpc

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.GitTestConnectionResponseDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.service.IGitConfigurationService

import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class GitConfigurationRpcImpl(
    private val gitConfigurationService: com.jervis.service.GitConfigurationService,
) : IGitConfigurationService {
    override suspend fun setupGitConfiguration(
        clientId: String,
        request: GitSetupRequestDto,
    ): ClientDto = gitConfigurationService.setupGitConfiguration(clientId, request)

    override suspend fun testConnection(
        clientId: String,
        request: GitSetupRequestDto,
    ): GitTestConnectionResponseDto = gitConfigurationService.testConnection(clientId, request)

    override suspend fun cloneRepository(clientId: String): CloneResultDto =
        gitConfigurationService.cloneRepository(clientId)

    override suspend fun inheritGitConfig(
        projectId: String,
        clientId: String,
    ): Map<String, Any> = gitConfigurationService.inheritGitConfig(projectId, clientId)

    override suspend fun getGitCredentials(clientId: String): GitCredentialsDto? =
        gitConfigurationService.getGitCredentials(clientId)

    override suspend fun listRemoteBranches(
        clientId: String,
        repoUrl: String?,
    ): GitBranchListDto = gitConfigurationService.listRemoteBranches(clientId, repoUrl)

    override suspend fun setDefaultBranch(
        clientId: String,
        branch: String,
    ): ClientDto = gitConfigurationService.setDefaultBranch(clientId, branch)

    override suspend fun setupGitOverrideForProject(
        projectId: String,
        request: ProjectGitOverrideRequestDto,
    ): ProjectDto = gitConfigurationService.setupGitOverrideForProject(projectId, request)

    override suspend fun clearGitOverrideForProject(projectId: String): ProjectDto =
        gitConfigurationService.clearGitOverrideForProject(projectId)
}
