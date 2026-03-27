package com.jervis.git.rpc

import com.jervis.dto.client.ClientDto
import com.jervis.dto.git.CloneResultDto
import com.jervis.dto.git.GitBranchListDto
import com.jervis.dto.git.GitCredentialsDto
import com.jervis.dto.git.GitSetupRequestDto
import com.jervis.dto.git.GitTestConnectionResponseDto
import com.jervis.dto.project.ProjectDto
import com.jervis.dto.project.ProjectGitOverrideRequestDto
import com.jervis.service.git.IGitConfigurationService

import com.jervis.infrastructure.error.ErrorLogService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class GitConfigurationRpcImpl(
    private val gitConfigurationService: com.jervis.git.GitConfigurationService,
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
