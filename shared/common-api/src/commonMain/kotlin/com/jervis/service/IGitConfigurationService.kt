package com.jervis.service

import com.jervis.dto.ClientDto
import com.jervis.dto.CloneResultDto
import com.jervis.dto.GitBranchListDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.GitSetupRequestDto
import com.jervis.dto.GitTestConnectionResponseDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectGitOverrideRequestDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IGitConfigurationService {
    /**
     * Sets up complete Git configuration for a client including credentials.
     * Credentials are encrypted and stored directly in ClientDocument.
     *
     * @param clientId The client ID
     * @param request Complete Git setup including provider, auth type, credentials, and config
     * @return Updated ClientDto with Git configuration
     */
    suspend fun setupGitConfiguration(
        clientId: String,
        request: GitSetupRequestDto,
    ): ClientDto

    /**
     * Test Git repository connection with provided credentials.
     * Validates that the repository is accessible before saving configuration.
     *
     * @param clientId The client ID
     * @param request Git setup request with credentials to test
     * @return Map with success status and message
     */
    suspend fun testConnection(
        clientId: String,
        request: GitSetupRequestDto,
    ): GitTestConnectionResponseDto

    /**
     * Clone client's mono-repository to local storage.
     * Requires Git configuration to be set up first.
     *
     * @param clientId The client ID
     * @return Clone result
     */
    suspend fun cloneRepository(clientId: String): CloneResultDto

    /**
     * Inherit Git configuration from client to project.
     * Optionally allows project-specific configuration overrides.
     *
     * @param projectId The project ID
     * @param clientId The client ID to inherit from
     * @return Map with success status
     */
    suspend fun inheritGitConfig(
        projectId: String,
        clientId: String,
    ): Map<String, Any>

    /**
     * Retrieves existing Git credentials for a client (masked).
     * Sensitive fields are not returned, only their presence is indicated.
     *
     * @param clientId The client ID
     * @return GitCredentialsDto with masked credentials, or null if not found
     */
    suspend fun getGitCredentials(clientId: String): GitCredentialsDto?

    /**
     * List remote branches for a client's configured repository (or provided repoUrl override).
     * Returns discovered default branch (if resolvable) and list of remote branch names.
     */
    suspend fun listRemoteBranches(
        clientId: String,
        repoUrl: String? = null,
    ): GitBranchListDto

    /**
     * Update client's default branch to the selected remote branch.
     */
    suspend fun setDefaultBranch(
        clientId: String,
        branch: String,
    ): ClientDto

    /**
     * Setup Git override configuration for a project.
     * Allows projects to use different credentials, remote URL, and auth type than the client.
     *
     * @param projectId The project ID
     * @param request The Git override configuration including credentials
     * @return Updated ProjectDto with new Git overrides
     */
    suspend fun setupGitOverrideForProject(
        projectId: String,
        request: ProjectGitOverrideRequestDto,
    ): ProjectDto

    /**
     * Clear Git override configuration for a project.
     * Project will revert to using client's Git configuration.
     *
     * @param projectId The project ID
     * @return Updated ProjectDto without Git overrides
     */
    suspend fun clearGitOverrideForProject(projectId: String): ProjectDto
}
