package com.jervis.repository

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.GitTestConnectionResponseDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import com.jervis.service.IGitConfigurationService

/**
 * Repository for Git configuration operations.
 * Wraps IGitConfigurationService for use by UI.
 */
class GitConfigurationRepository(
    private val service: IGitConfigurationService,
) : BaseRepository() {
    suspend fun setupGitConfiguration(clientId: String, request: GitSetupRequestDto): ClientDto =
        safeRpcCall("setupGitConfiguration") { service.setupGitConfiguration(clientId, request) }

    suspend fun testConnection(clientId: String, request: GitSetupRequestDto): GitTestConnectionResponseDto =
        safeRpcCall("testConnection") { service.testConnection(clientId, request) }

    suspend fun cloneRepository(clientId: String): CloneResultDto =
        safeRpcCall("cloneRepository") { service.cloneRepository(clientId) }

    suspend fun inheritGitConfigToProject(projectId: String, clientId: String): Map<String, Any> =
        safeRpcCall("inheritGitConfig") { service.inheritGitConfig(projectId, clientId) }

    suspend fun getGitCredentials(clientId: String): GitCredentialsDto? =
        safeRpcCall("getGitCredentials") { service.getGitCredentials(clientId) }

    suspend fun listRemoteBranches(clientId: String, repoUrl: String? = null): GitBranchListDto =
        safeRpcCall("listRemoteBranches") { service.listRemoteBranches(clientId, repoUrl) }

    suspend fun setDefaultBranch(clientId: String, branch: String): ClientDto =
        safeRpcCall("setDefaultBranch") { service.setDefaultBranch(clientId, branch) }

    suspend fun setupGitOverrideForProject(projectId: String, request: ProjectGitOverrideRequestDto): ProjectDto =
        safeRpcCall("setupGitOverrideForProject") { service.setupGitOverrideForProject(projectId, request) }
}
