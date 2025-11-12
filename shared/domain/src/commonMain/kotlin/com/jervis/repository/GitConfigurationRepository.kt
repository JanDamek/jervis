package com.jervis.repository

import com.jervis.dto.ClientDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.service.IGitConfigurationService

/**
 * Repository for Git configuration operations.
 * Wraps IGitConfigurationService for use by UI.
 */
class GitConfigurationRepository(
    private val service: IGitConfigurationService,
) {
    suspend fun setupGitConfiguration(clientId: String, request: GitSetupRequestDto): ClientDto =
        service.setupGitConfiguration(clientId, request)

    suspend fun testConnection(clientId: String, request: GitSetupRequestDto): Map<String, Any> =
        service.testConnection(clientId, request)

    suspend fun cloneRepository(clientId: String) =
        service.cloneRepository(clientId)

    suspend fun inheritGitConfigToProject(projectId: String, clientId: String): Map<String, Any> =
        service.inheritGitConfig(projectId, clientId)

    suspend fun getGitCredentials(clientId: String): GitCredentialsDto? =
        service.getGitCredentials(clientId)

    suspend fun listRemoteBranches(clientId: String, repoUrl: String? = null): GitBranchListDto =
        service.listRemoteBranches(clientId, repoUrl)

    suspend fun setDefaultBranch(clientId: String, branch: String): ClientDto =
        service.setDefaultBranch(clientId, branch)

    suspend fun setupGitOverrideForProject(projectId: String, request: ProjectGitOverrideRequestDto): ProjectDto =
        service.setupGitOverrideForProject(projectId, request)
}
